package org.hongxi.jaws.sample.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Created by shenhongxi on 2021/4/26.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Phone implements Serializable {
    private static final long serialVersionUID = -56932839374275492L;

    private Integer number;
}
