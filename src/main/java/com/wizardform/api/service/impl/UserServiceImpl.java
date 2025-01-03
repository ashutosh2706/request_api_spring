package com.wizardform.api.service.impl;

import com.wizardform.api.dto.PagedResponseDto;
import com.wizardform.api.dto.UserDto;
import com.wizardform.api.dto.UserResponseDTO;
import com.wizardform.api.exception.RoleNotFoundException;
import com.wizardform.api.exception.UserNotFoundException;
import com.wizardform.api.helper.Utils;
import com.wizardform.api.mapper.UserMapper;
import com.wizardform.api.model.Request;
import com.wizardform.api.model.Role;
import com.wizardform.api.model.User;
import com.wizardform.api.repository.UserRepository;
import com.wizardform.api.service.RoleService;
import com.wizardform.api.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleService roleService;

    @Autowired
    public UserServiceImpl(UserRepository userRepository, RoleService roleService) {
        this.userRepository = userRepository;
        this.roleService = roleService;
    }

    @Override
    public PagedResponseDto<UserResponseDTO> getAllUsers(String searchTerm, int pageNumber, int pageSize, String sortField, String sortDirection) throws IllegalArgumentException {

        String resolvedSortField = sortField.trim().isEmpty() ? "userId" : resolveSortField(sortField);
        if(resolvedSortField == null) {
            log.error("IllegalArgumentException: Invalid sort parameter {}", sortField);
            throw new IllegalArgumentException("Invalid sort field: " + sortField);
        }

        Sort sort = Sort.by(sortDirection.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, resolvedSortField);
        PageRequest pageRequest = PageRequest.of(pageNumber - 1, pageSize, sort);
        Page<User> userPage = userRepository.findAll(pageRequest);
        List<User> users = userPage.getContent();
        List<UserResponseDTO> result = new ArrayList<>();

        for(User user: users) {
            UserResponseDTO userResponseDTO = new UserResponseDTO();
            userResponseDTO.setUserId(user.getUserId());
            userResponseDTO.setFirstName(user.getFirstName());
            userResponseDTO.setLastName(user.getLastName());
            userResponseDTO.setEmail(user.getEmail());
            userResponseDTO.setAllowed(user.getIsActive());
            userResponseDTO.setRoleId(user.getRole().getRoleId());
            result.add(userResponseDTO);
        }

        return new PagedResponseDto<>(pageNumber, userPage.getNumberOfElements(), userPage.getTotalPages(), userPage.getTotalElements(), result);
    }

    /**
     * WARNING!!!
     * @return UserDTO when using in controller
     */
    @Override
    public User getUserByUserId(long userId) throws UserNotFoundException {
        Optional<User> optionalUser = userRepository.findByUserId(userId);
        return optionalUser.orElseThrow(() -> {
            log.error("UserNotFoundException: No user was found with userId {}", userId);
            return new UserNotFoundException("User with id (" + userId + ") was not found");
        });
    }

    @Override
    public void changeRole(long userId, int roleId) throws RoleNotFoundException{
        Optional<User> existingUser = userRepository.findByUserId(userId);
        Role role = roleService.getRoleByRoleId(roleId);
        if(existingUser.isPresent() && role != null) {
            User user = existingUser.get();
            user.setRole(role);
            userRepository.save(user);
        } else {
            log.error("EntityNotFoundException: Role with roleId {} and/or User with userId {} was not found", roleId, userId);
            throw new EntityNotFoundException("Requested entity was not found.");
        }
    }

    @Override
    public UserResponseDTO addUser(UserDto userDTO) throws RoleNotFoundException {
        Role existingRole = roleService.getRoleByRoleId(userDTO.getRoleId());
        if(existingRole != null) {
            User user = UserMapper.INSTANCE.userDTOToUser(userDTO);
            user.setPassword(Utils.generateHash(userDTO.getPassword()));
            user.setIsActive(false);
            user.setRole(existingRole);
            User savedUser = userRepository.save(user);
            return UserMapper.INSTANCE.userToUserResponseDTO(savedUser);
        } else {
            log.error("RoleNotFoundException: No role was found with roleId {}", userDTO.getRoleId());
            throw new RoleNotFoundException("Role with id: " + userDTO.getRoleId() + " does not exist");
        }
    }

    @Override
    @Transactional
    public void allowUser(long userId) throws UserNotFoundException {
        Optional<User> existingUser = userRepository.findByUserId(userId);
        if(existingUser.isPresent()) {
            User user = existingUser.get();
            user.setIsActive(!user.getIsActive());
            userRepository.save(user);
        } else {
            log.error("UserNotFoundException: No user found with userId {}", userId);
            throw new UserNotFoundException("User with id: " + userId + " was not found");
        }
    }

    @Override
    @Transactional
    public void deleteUser(long userId) throws UserNotFoundException {
        Optional<User> existingUser = userRepository.findByUserId(userId);
        if(existingUser.isPresent()) {
            userRepository.delete(existingUser.get());
        } else {
            log.error("UserNotFoundException: No user found with userId {}", userId);
            throw new UserNotFoundException("User with id: " + userId + " was not found");
        }
    }

    /**
     * WARNING!!!
     * @return UserResponseDTO when using in controller
     */
    /// email is unique, so it will return unique user
    /// two methods to get user from db, 1. by userId (accessible to admin only) 2. by email (accessible to end users)
    @Override
    public User getUserByEmail(String email) throws UserNotFoundException {
        Optional<User> optionalUser = userRepository.findByEmail(email);
        return optionalUser.orElseThrow(() -> {
            log.error("UserNotFoundException: No user found with email {}", email);
            return new UserNotFoundException("User with email (" + email + ") was not found");
        });
    }

    // Service for security config
    @Override
    public UserDetailsService userDetailsService() {
        return new UserDetailsService() {
            @Override
            public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
                Optional<User> optionalUser = userRepository.findByEmail(username);
                return optionalUser.orElseThrow(() -> {
                    log.error("UsernameNotFoundException: No user found with email {}", username);
                    return new UsernameNotFoundException("User with email (" + username + ") was not found");
                });
            }
        };
    }

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
