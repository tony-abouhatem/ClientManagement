package com.datum.clientmanagement.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = CustomerLimits.NAME_MAX_LENGTH)
    private String name;

    @Column(name = "name_normalized", nullable = false, length = CustomerLimits.NAME_MAX_LENGTH)
    private String nameNormalized;

    // The column is CHAR(11); without the explicit JDBC type, schema validation expects VARCHAR.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cpf", nullable = false, length = 11, updatable = false)
    private String cpf;

    @Column(name = "email", nullable = false, length = CustomerLimits.EMAIL_MAX_LENGTH)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CustomerStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Customer() {
        // for JPA
    }

    private Customer(String name, String cpf, String email, CustomerStatus status, Instant now) {
        rename(name);
        this.cpf = cpf;
        this.email = email;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** New customer; {@code cpf} must already be normalized and valid. */
    public static Customer create(String name, String cpf, String email, CustomerStatus status, Instant now) {
        return new Customer(name, cpf, email, status, now);
    }

    public void update(String name, String email, CustomerStatus status, Instant now) {
        rename(name);
        this.email = email;
        this.status = status;
        this.updatedAt = now;
    }

    private void rename(String newName) {
        this.name = newName;
        this.nameNormalized = CustomerFormats.searchableName(newName);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNameNormalized() {
        return nameNormalized;
    }

    public String getCpf() {
        return cpf;
    }

    public String getEmail() {
        return email;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
