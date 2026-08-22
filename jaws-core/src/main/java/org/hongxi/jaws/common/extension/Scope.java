package org.hongxi.jaws.common.extension;

/**
 * Instance scope of an SPI extension, declared via {@link Spi#scope()} and
 * honored by {@link ExtensionLoader} when creating instances.
 */
public enum Scope {

    /** A single shared instance per extension name, created lazily and cached. */
    SINGLETON,

    /** A new instance created on every {@link ExtensionLoader#getExtension} call. */
    PROTOTYPE
}