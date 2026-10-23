package ru.itmo.highload_ml.shared.exception;

public class NotFoundException extends DomainException {

    public NotFoundException(String message) {
        super(message);
    }
}
