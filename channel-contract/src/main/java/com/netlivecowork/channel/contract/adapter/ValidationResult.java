package com.netlivecowork.channel.contract.adapter;

/** 配置校验结果：字段级问题，不执行联网检测。 */
public record ValidationResult(boolean valid, java.util.List<ValidationIssue> issues) {

    public ValidationResult {
        issues = issues == null ? java.util.List.of() : java.util.List.copyOf(issues);
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, java.util.List.of());
    }

    public static ValidationResult failed(java.util.List<ValidationIssue> issues) {
        return new ValidationResult(false, issues);
    }

    public static ValidationResult failed(String path, String code, String message) {
        return failed(java.util.List.of(new ValidationIssue(path, code, message)));
    }

    public record ValidationIssue(String path, String code, String message) {
        public ValidationIssue {
            path = path == null || path.isBlank() ? "$" : path;
            code = code == null || code.isBlank() ? "INVALID" : code;
            message = message == null ? "" : message;
        }
    }
}
