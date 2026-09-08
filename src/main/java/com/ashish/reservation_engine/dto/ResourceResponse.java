package com.ashish.reservation_engine.dto;

import com.ashish.reservation_engine.entity.Resource;

public class ResourceResponse {

    private Long id;
    private String name;
    private Integer totalCapacity;
    private Integer availableCapacity;

    public ResourceResponse() {
    }

    public ResourceResponse(Long id, String name, Integer totalCapacity, Integer availableCapacity) {
        this.id = id;
        this.name = name;
        this.totalCapacity = totalCapacity;
        this.availableCapacity = availableCapacity;
    }

    public ResourceResponse(Resource resource) {
        this.id = resource.getId();
        this.name = resource.getName();
        this.totalCapacity = resource.getTotalCapacity();
        this.availableCapacity = resource.getAvailableCapacity();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getTotalCapacity() {
        return totalCapacity;
    }

    public void setTotalCapacity(Integer totalCapacity) {
        this.totalCapacity = totalCapacity;
    }

    public Integer getAvailableCapacity() {
        return availableCapacity;
    }

    public void setAvailableCapacity(Integer availableCapacity) {
        this.availableCapacity = availableCapacity;
    }

    @Override
    public String toString() {
        return "ResourceResponse{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", totalCapacity=" + totalCapacity +
                ", availableCapacity=" + availableCapacity +
                '}';
    }
}

