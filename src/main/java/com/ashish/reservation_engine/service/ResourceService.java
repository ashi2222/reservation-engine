package com.ashish.reservation_engine.service;

import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.redis.RedisResourceService;
import com.ashish.reservation_engine.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final RedisResourceService redisResourceService;

    public ResourceService(ResourceRepository resourceRepository, RedisResourceService redisResourceService) {
        this.resourceRepository = resourceRepository;
        this.redisResourceService = redisResourceService;
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
        Resource saved = resourceRepository.save(resource);
        redisResourceService.initializeCapacity(saved.getId(), saved.getAvailableCapacity());
        return saved;
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
        Resource saved = resourceRepository.save(resource);
        redisResourceService.initializeCapacity(saved.getId(), saved.getAvailableCapacity());
        return saved;
    }

    public Resource getResourceById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Resource ID must not be null");
        }
        Resource resource = resourceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Resource not found with id: " + id));

        if (!redisResourceService.hasResourceCapacity(id)) {
            redisResourceService.initializeCapacity(id, resource.getAvailableCapacity());
        }
        return resource;
    }
}
