package ru.itmo.highload_ml.shared.exception;

public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(message);
    }
}
