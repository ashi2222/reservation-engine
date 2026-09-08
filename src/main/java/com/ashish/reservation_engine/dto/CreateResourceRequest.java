package com.ashish.reservation_engine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class CreateResourceRequest {

    @NotBlank(message = "Resource name must not be blank")
    private String name;

    @NotNull(message = "Total capacity is required")
    @Positive(message = "Total capacity must be greater than zero")
    private Integer totalCapacity;

    public CreateResourceRequest() {
    }

    public CreateResourceRequest(String name, Integer totalCapacity) {
        this.name = name;
        this.totalCapacity = totalCapacity;
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

    @Override
    public String toString() {
        return "CreateResourceRequest{" +
                "name='" + name + '\'' +
                ", totalCapacity=" + totalCapacity +
                '}';
    }
}
