package com.quorum.tessera.config;

import com.quorum.tessera.config.adapters.MapAdapter;

import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.*;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@XmlType(name = "keyVaultConfig")
@XmlAccessorType(XmlAccessType.FIELD)
public class DefaultKeyVaultConfig extends ConfigItem implements KeyVaultConfig {

    @NotNull
    @XmlAttribute
    private KeyVaultType keyVaultType;

    @XmlJavaTypeAdapter(MapAdapter.class)
    @XmlElement
    private Map<String, String> properties = new HashMap<>();

    public DefaultKeyVaultConfig() {}

    public DefaultKeyVaultConfig setProperty(String name, String value) {
        this.properties.put(name, value);
        return this;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public KeyVaultType getKeyVaultType() {
        return keyVaultType;
    }

    public void setKeyVaultType(KeyVaultType keyVaultType) {
        this.keyVaultType = keyVaultType;
    }
}
