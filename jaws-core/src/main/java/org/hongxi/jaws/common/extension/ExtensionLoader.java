package org.hongxi.jaws.common.extension;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by shenhongxi on 2020/6/25.
 */
public class ExtensionLoader<T> {
    private static final Logger log = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> extensionLoaders = new ConcurrentHashMap<>();
    private final Class<T> type;
    private final Scope scope;
    private final ClassLoader classLoader;
    private ConcurrentMap<String, Class<T>> extensionClasses;
    private ConcurrentMap<String, T> singletonInstances;
    private Map<Integer, String> numberToName = Collections.emptyMap();
    private volatile boolean init;

    private ExtensionLoader(Class<T> type) {
        this(type, Thread.currentThread().getContextClassLoader());
    }

    private ExtensionLoader(Class<T> type, ClassLoader classLoader) {
        this.type = type;
        this.classLoader = classLoader;
        this.scope = type.getAnnotation(Spi.class).scope();
    }

    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        checkInterfaceType(type);

        // noinspection unchecked
        ExtensionLoader<T> loader = (ExtensionLoader<T>) extensionLoaders.get(type);
        if (loader == null) {
            loader = initExtensionLoader(type);
        }
        return loader;
    }

    private static <T> void checkInterfaceType(Class<T> clazz) {
        if (!clazz.isInterface()) {
            throw new JawsFrameworkException(clazz.getName() + ": Extension type is not interface");
        }
        if (!clazz.isAnnotationPresent(Spi.class)) {
            throw new JawsFrameworkException(clazz.getName() + ": Extension type without @Spi annotation");
        }
    }

    private static synchronized <T> ExtensionLoader<T> initExtensionLoader(Class<T> type) {
        // noinspection unchecked
        ExtensionLoader<T> loader = (ExtensionLoader<T>) extensionLoaders.get(type);
        if (loader == null) {
            loader = new ExtensionLoader<>(type);
            extensionLoaders.put(type, loader);
        }
        return loader;
    }

    public T getExtension(String name) {
        if (name == null) return null;

        checkInit();

        try {
            if (scope == Scope.SINGLETON) {
                return getSingletonInstance(name);
            }

            Class<T> clazz = extensionClasses.get(name);
            if (clazz == null) {
                return null;
            }
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new JawsFrameworkException(type.getName() + ": Error get extension " + name, e);
        }
    }

    private T getSingletonInstance(String name) {
        Class<T> clazz = extensionClasses.get(name);
        if (clazz == null) {
            return null;
        }
        return singletonInstances.computeIfAbsent(name, k -> {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new JawsFrameworkException(type.getName() + ": Error creating singleton extension " + name, e);
            }
        });
    }

    /**
     * Get SPI extension names matching the given activation key.
     *
     * @param key the activation key to match, blank means all extensions
     * @return list of SPI extension names
     */
    public List<String> getExtensionNames(String key) {
        checkInit();

        if (extensionClasses.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> names = new ArrayList<>(extensionClasses.size());
        for (Map.Entry<String, Class<T>> entry : extensionClasses.entrySet()) {
            Activation activation = entry.getValue().getAnnotation(Activation.class);
            if (StringUtils.isBlank(key)) {
                names.add(entry.getKey());
            } else if (activation != null && activation.value() != null) {
                for (String k : activation.value()) {
                    if (key.equals(k)) {
                        names.add(entry.getKey());
                        break;
                    }
                }
            }
        }
        return names;
    }

    private synchronized void checkInit() {
        if (init) return;

        extensionClasses = loadExtensionClasses();
        singletonInstances = new ConcurrentHashMap<>();

        init = true;
    }

    private ConcurrentMap<String, Class<T>> loadExtensionClasses() {
        String fullName = SERVICES_DIRECTORY + type.getName();
        Set<String> classNames = new LinkedHashSet<>();

        try {
            Enumeration<URL> urls;
            if (classLoader == null) {
                urls = ClassLoader.getSystemResources(fullName);
            } else {
                urls = classLoader.getResources(fullName);
            }

            if (urls == null || !urls.hasMoreElements()) {
                return new ConcurrentHashMap<>();
            }

            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                parseUrl(type, url, classNames);
            }
        } catch (Exception e) {
            throw new JawsFrameworkException(
                    "ExtensionLoader loadExtensionClasses error, services dir: " + SERVICES_DIRECTORY + ", type: " + type, e);
        }

        return loadClasses(classNames);
    }

    private void parseUrl(Class<T> type, URL url, Set<String> classNames) {
        try (InputStream inputStream = url.openStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                parseLine(type, url, line, ++lineNumber, classNames);
            }
        } catch (IOException e) {
            log.error("{}: Error reading extension configuration file", type.getName(), e);
        }
    }

    private void parseLine(Class<T> type, URL url, String line, int lineNumber, Set<String> classNames) {
        int ci = line.indexOf('#');
        if (ci > 0) line = line.substring(0, ci);
        line = line.trim();

        if (line.isEmpty()) return;

        if (line.indexOf(' ') >= 0 || line.indexOf('\t') >= 0) {
            throw new JawsFrameworkException(type.getName() + ": " + url + ": " + lineNumber + ": Illegal extension configuration-file syntax");
        }

        int cp = line.codePointAt(0);
        if (!Character.isJavaIdentifierStart(cp)) {
            throw new JawsFrameworkException(type.getName() + ": " + url + ": " + lineNumber + ": Illegal extension provider-class name: " + line);
        }

        classNames.add(line);
    }

    private ConcurrentMap<String, Class<T>> loadClasses(Set<String> classNames) {
        ConcurrentMap<String, Class<T>> classes = new ConcurrentHashMap<>();
        Map<Integer, String> numberMap = new HashMap<>();
        for (String className : classNames) {
            try {
                Class<T> clazz;
                if (classLoader == null) {
                    // noinspection unchecked
                    clazz = (Class<T>) Class.forName(className);
                } else {
                    // noinspection unchecked
                    clazz = (Class<T>) Class.forName(className, true, classLoader);
                }

                checkExtensionType(clazz);

                String extName = getExtensionName(clazz);
                if (classes.containsKey(extName)) {
                    throw new JawsFrameworkException(clazz + ": extension name already exists: " + extName);
                } else {
                    classes.put(extName, clazz);
                }

                // Build number → name mapping for extensions that declare a number
                Extension ext = clazz.getAnnotation(Extension.class);
                if (ext != null && ext.number() >= 0) {
                    numberMap.put((int) ext.number(), extName);
                }
            } catch (Exception e) {
                log.error("{}: Error loading extension class", type.getName(), e);
            }
        }
        if (!numberMap.isEmpty()) {
            this.numberToName = numberMap;
        }

        return classes;
    }

    private void checkExtensionType(Class<T> clazz) {
        checkClassPublic(clazz);
        checkConstructorPublic(clazz);
        checkClassInherit(clazz);
    }

    private void checkClassPublic(Class<T> clazz) {
        if (!Modifier.isPublic(clazz.getModifiers())) {
            throw new JawsFrameworkException(clazz.getName() + "is not a public class");
        }
    }

    private void checkConstructorPublic(Class<T> clazz) {
        Constructor<?>[] constructors = clazz.getConstructors();
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterTypes().length == 0) {
                return;
            }
        }
        throw new JawsFrameworkException(clazz.getName() + " has no public no-args constructor");
    }

    private void checkClassInherit(Class<T> clazz) {
        if (!type.isAssignableFrom(clazz)) {
            throw new JawsFrameworkException(clazz.getName() + "is not instanceof " + type.getName());
        }
    }

    /**
     * Get extension by its numeric identifier declared in {@link Extension#number()}.
     *
     * @param number the numeric identifier (0-31)
     * @return the extension instance, or null if not found
     */
    public T getExtensionByNumber(int number) {
        checkInit();
        String name = numberToName.get(number);
        return name != null ? getExtension(name) : null;
    }

    private String getExtensionName(Class<?> clazz) {
        Extension ext = clazz.getAnnotation(Extension.class);
        return ext != null ? ext.value() : clazz.getSimpleName();
    }
}