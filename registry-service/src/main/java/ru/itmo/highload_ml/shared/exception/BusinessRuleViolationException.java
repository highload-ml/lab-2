package ru.itmo.highload_ml.shared.exception;

public class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String message) {
        super(message);
    }
}
