package org.hongxi.jaws.sample.api.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Created by shenhongxi on 2021/4/26.
 */
public class Contacts implements Serializable {
    @Serial
    private static final long serialVersionUID = 9095342848908217853L;

    private Long id;

    private User user;

    private List<Phone> phones;

    private List<String> addresses;

    public Contacts() {
    }

    public Contacts(Long id, User user, List<Phone> phones, List<String> addresses) {
        this.id = id;
        this.user = user;
        this.phones = phones;
        this.addresses = addresses;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public List<Phone> getPhones() {
        return phones;
    }

    public void setPhones(List<Phone> phones) {
        this.phones = phones;
    }

    public List<String> getAddresses() {
        return addresses;
    }

    public void setAddresses(List<String> addresses) {
        this.addresses = addresses;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Contacts contacts = (Contacts) o;
        return Objects.equals(id, contacts.id) && Objects.equals(user, contacts.user) && Objects.equals(phones, contacts.phones) && Objects.equals(addresses, contacts.addresses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, user, phones, addresses);
    }

    @Override
    public String toString() {
        return "Contacts{id=" + id + ", user=" + user + ", phones=" + phones + ", addresses=" + addresses + "}";
    }
}
