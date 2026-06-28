package org.hongxi.jaws.sample.api.model;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 订单模型
 */
public class Order implements Serializable {

    @Serial
    private static final long serialVersionUID = 7295432871098765432L;

    private Long id;

    private String orderNo;

    private User buyer;

    private BigDecimal amount;

    private Integer status;

    private List<String> items;

    private LocalDateTime createTime;

    public Order() {
    }

    public Order(Long id, String orderNo, User buyer, BigDecimal amount) {
        this.id = id;
        this.orderNo = orderNo;
        this.buyer = buyer;
        this.amount = amount;
        this.status = 0;
        this.createTime = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public User getBuyer() {
        return buyer;
    }

    public void setBuyer(User buyer) {
        this.buyer = buyer;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public List<String> getItems() {
        return items;
    }

    public void setItems(List<String> items) {
        this.items = items;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Order order = (Order) o;
        return Objects.equals(id, order.id) && Objects.equals(orderNo, order.orderNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, orderNo);
    }

    @Override
    public String toString() {
        return "Order{id=" + id + ", orderNo='" + orderNo + "', buyer=" + buyer + ", amount=" + amount + ", status=" + status + "}";
    }
}
