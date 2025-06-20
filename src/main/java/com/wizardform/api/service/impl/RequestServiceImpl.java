package com.wizardform.api.service.impl;

import com.wizardform.api.Constants;
import com.wizardform.api.dto.PagedResponseDto;
import com.wizardform.api.dto.NewRequestDto;
import com.wizardform.api.dto.RequestDto;
import com.wizardform.api.exception.PriorityNotFoundException;
import com.wizardform.api.exception.StatusNotFoundException;
import com.wizardform.api.exception.UserNotFoundException;
import com.wizardform.api.exception.RequestNotFoundException;
import com.wizardform.api.exception.FileDetailsNotFoundException;
import com.wizardform.api.mapper.RequestMapper;
import com.wizardform.api.model.FileDetail;
import com.wizardform.api.model.Priority;
import com.wizardform.api.model.Request;
import com.wizardform.api.model.User;
import com.wizardform.api.model.Status;
import com.wizardform.api.repository.RequestRepository;
import com.wizardform.api.service.RequestService;
import com.wizardform.api.service.StatusService;
import com.wizardform.api.service.PriorityService;
import com.wizardform.api.service.UserService;
import com.wizardform.api.service.FileService;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class RequestServiceImpl implements RequestService {

    private final RequestRepository requestRepository;
    private final StatusService statusService;
    private final PriorityService priorityService;
    private final UserService userService;
    private final FileService fileService;

    @Autowired
    public RequestServiceImpl(RequestRepository requestRepository, PriorityService priorityService, StatusService statusService, UserService userService, FileService fileService) {
        this.requestRepository = requestRepository;
        this.priorityService = priorityService;
        this.statusService = statusService;
        this.userService = userService;
        this.fileService = fileService;
    }

    @Override
    public PagedResponseDto<RequestDto> getAllRequests(String searchTerm, int pageNumber, int pageSize, String sortField, String sortDirection) throws IllegalArgumentException {
        // default sort field is requestId if none provided
        // validation check: if the value provided in sortField is a valid property or not
        String resolvedSortField = sortField.trim().isEmpty() ? "requestId" : resolveSortField(sortField);
        if(resolvedSortField == null) {
            log.error("IllegalArgumentException: Invalid sort parameter {}", sortField);
            throw new IllegalArgumentException("Invalid sort field: " + sortField);
        }

        Sort sort = Sort.by(sortDirection.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, resolvedSortField);
        PageRequest pageRequest = PageRequest.of(pageNumber - 1, pageSize, sort);

        Page<Request> requestPage = requestRepository.findAll(pageRequest);
        List<Request> requests = requestPage.getContent();
        List<RequestDto> newRequestDtoList = RequestMapper.INSTANCE.requestListToRequestDtoList(requests);
        return new PagedResponseDto<>(pageNumber, requestPage.getNumberOfElements(), requestPage.getTotalPages(), requestPage.getTotalElements(), newRequestDtoList);
    }

    @Override
    @Transactional
    public RequestDto addNewRequest(NewRequestDto newRequestDto) throws UserNotFoundException, PriorityNotFoundException, StatusNotFoundException, IOException {

        Request newRequest = RequestMapper.INSTANCE.newRequestDtoToRequest(newRequestDto);
        MultipartFile attachedFile = newRequestDto.getAttachedFile();
        FileDetail savedFileDetail = null;
        User userFromDb = userService.getUserByUserId(newRequestDto.getUserId());
        Priority priorityFromDb = priorityService.getPriorityByPriorityCode(newRequestDto.getPriorityCode());
        Status statusFromDb = statusService.getStatusByStatusCode(Constants.StatusCode.STATUS_PENDING);
        if(userFromDb.isEnabled()) {
            if(attachedFile != null) {
                savedFileDetail = fileService.saveFile(attachedFile);
            }
            newRequest.setUser(userFromDb);
            newRequest.setPriority(priorityFromDb);
            newRequest.setStatus(statusFromDb);
            newRequest.setFileDetail(savedFileDetail);

            Request savedRequest = requestRepository.save(newRequest);
            RequestDto requestDto = RequestMapper.INSTANCE.requestToRequestDto(savedRequest);
            return requestDto;

        } else {
            log.error("UserNotFoundException: UserId {} doesn't exist or disabled", newRequestDto.getUserId());
            throw new UserNotFoundException("User with id: " + newRequestDto.getUserId() + " doesn't exist or disabled");
        }
    }

    @Override
    public PagedResponseDto<RequestDto> getAllRequestByUserId(long userId, String searchTerm, int pageNumber, int pageSize, String sortField, String sortDirection) throws IllegalArgumentException, UserNotFoundException {

        User existingUser = userService.getUserByUserId(userId);

        // default sort field is requestId if none provided
        // validation check: if the value provided in sortField is a valid property or not
        String resolvedSortField = sortField.trim().isEmpty() ? "requestId" : resolveSortField(sortField);
        if(resolvedSortField == null) {
            log.error("IllegalArgumentException: Invalid sort parameter {}", sortField);
            throw new IllegalArgumentException("Invalid sort field: " + sortField);
        }

        Sort sort = Sort.by(sortDirection.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, resolvedSortField);
        PageRequest pageRequest = PageRequest.of(pageNumber - 1, pageSize, sort);

        Page<Request> requestPage = requestRepository.findByUser(existingUser, pageRequest);
        List<Request> userRequests = requestPage.getContent();
        List<RequestDto> userRequestDtoList = RequestMapper.INSTANCE.requestListToRequestDtoList(userRequests);
        return new PagedResponseDto<>(pageNumber, requestPage.getNumberOfElements(), requestPage.getTotalPages(), requestPage.getTotalElements(), userRequestDtoList);
    }

    /// We use transactional when we want to perform all the operations as a single unit
    /// This is done to ensure atomicity of operations and to keep the db in a consistent state
    @Override
    @Transactional
    @Async("async-request-executor")
    public CompletableFuture<RequestDto> addNewRequestAsync(NewRequestDto newRequestDto) throws UserNotFoundException, PriorityNotFoundException, StatusNotFoundException, IOException {
        Request newRequest = RequestMapper.INSTANCE.newRequestDtoToRequest(newRequestDto);

        // To handle the attached file
        MultipartFile attachedFile = newRequestDto.getAttachedFile();
        FileDetail savedFileDetail = null;

        // new request status is always pending by default unless changed
        // priorityCode & userId will be provided by user
        User userFromDb = userService.getUserByUserId(newRequestDto.getUserId());
        Priority priorityFromDb = priorityService.getPriorityByPriorityCode(newRequestDto.getPriorityCode());
        Status statusFromDb = statusService.getStatusByStatusCode(Constants.StatusCode.STATUS_PENDING);
        if(userFromDb.isEnabled()) {
            if(attachedFile != null) {
                // call FileDetail service with multipart file argument. It will copy the file, save the details to db and return the FileDetail entity
                savedFileDetail = fileService.saveFile(attachedFile);
            }
            newRequest.setUser(userFromDb);
            newRequest.setPriority(priorityFromDb);
            newRequest.setStatus(statusFromDb);
            newRequest.setFileDetail(savedFileDetail);

            Request savedRequest = requestRepository.save(newRequest);
            RequestDto requestDto = RequestMapper.INSTANCE.requestToRequestDto(savedRequest);

            /// log the thread name which is handling this task
            log.info("Thread handling async request operations: {}", Thread.currentThread().getName());
            try {
                // simulate delay
                Thread.sleep(15000);
            } catch (InterruptedException e) {}

            return CompletableFuture.completedFuture(requestDto);

        } else {
            log.error("UserNotFoundException: UserId {} doesn't exist or disabled", newRequestDto.getUserId());
            throw new UserNotFoundException("User with id: " + newRequestDto.getUserId() + " doesn't exist or disabled");
        }
    }

    @Override
    public RequestDto getRequestByRequestId(long requestId) throws RequestNotFoundException {
        Optional<Request> requestOptional = requestRepository.findByRequestId(requestId);
        if(requestOptional.isPresent()) {
            Request request = requestOptional.get();
            return RequestMapper.INSTANCE.requestToRequestDto(request);
        } else {
            log.error("RequestNotFoundException: No request found with requestId {}", requestId);
            throw new RequestNotFoundException("Request with id: " + requestId + " was not found");
        }
    }

    @Override
    @Transactional
    public RequestDto updateRequest(NewRequestDto newRequestDto) throws RequestNotFoundException, UserNotFoundException, PriorityNotFoundException, StatusNotFoundException, FileDetailsNotFoundException, IOException {
        Optional<Request> requestOptional = requestRepository.findByRequestId(newRequestDto.getRequestId());
        if(requestOptional.isPresent()) {

            MultipartFile attachedFile = newRequestDto.getAttachedFile();
            FileDetail newFileDetail = null;

            Priority newPriority = priorityService.getPriorityByPriorityCode(newRequestDto.getPriorityCode());
            Status newStatus = statusService.getStatusByStatusCode(newRequestDto.getStatusCode());
            User newUser = userService.getUserByUserId(newRequestDto.getUserId());

            Request existingRequest = requestOptional.get();
            // update the existing request with new data
            existingRequest.setTitle(newRequestDto.getTitle());
            existingRequest.setGuardianName(newRequestDto.getGuardianName());
            existingRequest.setRequestDate(newRequestDto.getRequestDate());
            existingRequest.setPhone(newRequestDto.getPhone());
            existingRequest.setUser(newUser);
            existingRequest.setPriority(newPriority);
            existingRequest.setStatus(newStatus);

            if(attachedFile != null) {
                // existing file will be deleted along with it's details and new one will be copied and details saved to db
                long existingFileId = existingRequest.getFileDetail().getFileId();
                fileService.deleteFileDetails(existingFileId);
                newFileDetail = fileService.saveFile(attachedFile);
                existingRequest.setFileDetail(newFileDetail);
            }

            Request updatedRequest = requestRepository.save(existingRequest);
            return RequestMapper.INSTANCE.requestToRequestDto(updatedRequest);

        } else {
            log.error("RequestNotFoundException: No request found with requestId {}", newRequestDto.getRequestId());
            throw new RequestNotFoundException("Request with id: " + newRequestDto.getRequestId() + " was not found");
        }
    }

    @Override
    @Transactional
    public void deleteRequest(long requestId) throws RequestNotFoundException, FileDetailsNotFoundException {
        Optional<Request> requestOptional = requestRepository.findByRequestId(requestId);
        if(requestOptional.isPresent()) {
            // first delete the request and then file details (mark the order)
            requestRepository.delete(requestOptional.get());
            fileService.deleteFileDetails(requestOptional.get().getFileDetail().getFileId());
        } else {
            log.error("RequestNotFoundException: No request found with requestId {}", requestId);
            throw new RequestNotFoundException("Request with id: " + requestId + " was not found");
        }
    }

    // use reflection to validate if the given sortField is present
    // start with validating parent class fields, if none matches then check for nested fields
    // if sortField is found inside a nested instance field return it as [nestedField.field]
    private static String resolveSortField(String sortField) {
        if(isValidField(Request.class, sortField))
            return sortField;

        java.lang.Class<?> parentClass = Request.class;
        Field[] fields = parentClass.getDeclaredFields();
        for(Field field: fields) {
            if(isValidField(field.getType(), sortField)) {
                return field.getType().getSimpleName() + "." + sortField;
            }
        }
        return null;
    }

    private static boolean isValidField(Class<?> Class, String fieldName) {
        for (Field field: Class.getDeclaredFields()) {
            if(field.getName().equals(fieldName))
                return true;
        }
        return false;
    }
}
