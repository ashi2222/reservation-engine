package com.ashish.reservation_engine.service;

import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;

    public ResourceService(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @Transactional
    public Resource createResource(String name, Integer totalCapacity) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Resource name must not be blank");
        }
        if (totalCapacity == null || totalCapacity <= 0) {
            throw new IllegalArgumentException("Total capacity must be greater than zero");
        }
        Resource resource = new Resource(name, totalCapacity, totalCapacity);
        return resourceRepository.save(resource);
    }

    @Transactional
    public Resource createResource(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Resource must not be null");
        }
        if (resource.getName() == null || resource.getName().isBlank()) {
            throw new IllegalArgumentException("Resource name must not be blank");
        }
        if (resource.getTotalCapacity() == null || resource.getTotalCapacity() <= 0) {
            throw new IllegalArgumentException("Total capacity must be greater than zero");
        }
        resource.setAvailableCapacity(resource.getTotalCapacity());
        return resourceRepository.save(resource);
    }

    public Resource getResourceById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Resource ID must not be null");
        }
        return resourceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Resource not found with id: " + id));
    }
}
