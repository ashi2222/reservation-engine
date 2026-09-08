package com.ashish.reservation_engine.controller;

import com.ashish.reservation_engine.dto.CreateResourceRequest;
import com.ashish.reservation_engine.dto.ResourceResponse;
import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.service.ResourceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceResponse createResource(@Valid @RequestBody CreateResourceRequest request) {
        Resource resource = resourceService.createResource(request.getName(), request.getTotalCapacity());
        return new ResourceResponse(resource);
    }

    @GetMapping("/{id}")
    public ResourceResponse getResourceById(@PathVariable Long id) {
        Resource resource = resourceService.getResourceById(id);
        return new ResourceResponse(resource);
    }
}

