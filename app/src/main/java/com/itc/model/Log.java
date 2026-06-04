package com.itc.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Log {
    private Long id;
    private String level;
    private String source;
    private String message;
    private String stacktrace;
    private String payload;
    private String correlationId;
    private LocalDateTime timestamp;

    // Getters and setters
    // Without Lombok, we would have to write all the getters and setters manually. For brevity, they are not included here.
    // public Long getId() { return id; }
    // public void setId(Long id) { this.id = id; }
    // public String getLevel() { return level; }
    // public void setLevel(String level) { this.level = level; }
    // public String getSource() { return source; }
    // public void setSource(String source) { this.source = source; }
    // public String getMessage() { return message; }
    // public void setMessage(String message) { this.message = message; }
    // public LocalDateTime getTimestamp() { return timestamp; }
    // public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
