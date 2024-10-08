package com.wizardform.api.mapper;

import com.wizardform.api.dto.RequestDto;
import com.wizardform.api.model.Request;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper
public interface RequestMapper {

    RequestMapper INSTANCE = Mappers.getMapper(RequestMapper.class);
    @Mapping(source = "user.userId", target = "userId")
    @Mapping(source = "priority.priorityCode", target = "priorityCode")
    @Mapping(source = "status.statusCode", target = "statusCode")
    @Mapping(target = "attachedFile", ignore = true)
    List<RequestDto> requestListToRequestDtoList(List<Request> requests);

    @Mapping(target = "requestId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "priority", ignore = true)
    @Mapping(target = "fileDetail", ignore = true)
    Request RequestDtoToRequest(RequestDto requestDto);

    @Mapping(source = "user.userId", target = "userId")
    @Mapping(source = "priority.priorityCode", target = "priorityCode")
    @Mapping(source = "status.statusCode", target = "statusCode")
    @Mapping(target = "attachedFile", ignore = true)
    RequestDto requestToRequestDto(Request request);

}
