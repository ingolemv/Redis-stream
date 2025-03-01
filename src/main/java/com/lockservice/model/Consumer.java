package com.lockservice.model;

@FunctionalInterface
public interface Consumer<T> {
    void accept(T t);
} 