package rw.rra.roomiq.organization.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "country", uniqueConstraints = {
        @UniqueConstraint(name = "uq_country_name", columnNames = "name"),
        @UniqueConstraint(name = "uq_country_iso_code", columnNames = "iso_code")
})
public class Country extends OrganizationEntity {
    @Column(nullable = false, length = 150)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "iso_code", length = 2, columnDefinition = "CHAR(2)")
    private String isoCode;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Country() {
    }

    public Country(String name, String isoCode, boolean active) {
        this.name = name;
        this.isoCode = isoCode;
        this.active = active;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }

    public void updateProfile(String name, String isoCode) {
        this.name = name;
        this.isoCode = isoCode;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public String getIsoCode() {
        return isoCode;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}