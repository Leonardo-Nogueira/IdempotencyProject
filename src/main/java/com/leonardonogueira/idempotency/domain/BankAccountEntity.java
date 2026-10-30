package com.leonardonogueira.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "bank_account")
public class BankAccountEntity {

    @Id
    private Long id;

    @Column(nullable = false, length = 120)
    private String owner;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    protected BankAccountEntity() {}

    public BankAccountEntity(Long id, String owner, BigDecimal balance) {
        this.id = id;
        this.owner = owner;
        this.balance = balance;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
}

