package org.hongxi.jaws.sample.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Created by shenhongxi on 2021/4/26.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Contacts implements Serializable {
    private static final long serialVersionUID = 9095342848908217853L;

    private Long id;

    private User user;

    private List<Phone> phones;

    private List<String> addresses;
}
