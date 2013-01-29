/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <http://unlicense.org/>
 */

package net.adamcin.recap.impl;

import net.adamcin.recap.api.Recap;
import net.adamcin.recap.api.RecapAddress;
import net.adamcin.recap.api.RecapConstants;
import net.adamcin.recap.api.RecapOptions;
import net.adamcin.recap.api.RecapSession;
import net.adamcin.recap.api.RecapSessionException;
import org.apache.commons.lang.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.apache.jackrabbit.client.RepositoryFactoryImpl;
import org.apache.jackrabbit.jcr2spi.Jcr2spiRepositoryFactory;
import org.apache.jackrabbit.spi.Path;
import org.apache.jackrabbit.spi.commons.conversion.PathResolver;
import org.apache.jackrabbit.spi.commons.logging.Slf4jLogWriterProvider;
import org.apache.jackrabbit.spi2davex.BatchReadConfig;
import org.apache.jackrabbit.spi2davex.Spi2davexRepositoryServiceFactory;
import org.apache.sling.commons.osgi.OsgiUtil;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NamespaceException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;

/**
 * @author madamcin
 * @version $Id: RecapImpl.java$
 */
@Component(label = "Recap Service", metatype = true)
@Service
public class RecapImpl implements Recap {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecapImpl.class);

    @Property(label = "Default Remote Port", intValue = RecapConstants.DEFAULT_DEFAULT_PORT)
    private static final String OSGI_DEFAULT_PORT = "default.port";

    @Property(label = "Default Remote Username", value = RecapConstants.DEFAULT_DEFAULT_USERNAME)
    private static final String OSGI_DEFAULT_USERNAME = "default.username";

    @Property(label = "Default Remote Password", value = RecapConstants.DEFAULT_DEFAULT_PASSWORD)
    private static final String OSGI_DEFAULT_PASSWORD = "default.password";

    @Property(label = "Default Remote Context Path", value = RecapConstants.DEFAULT_DEFAULT_CONTEXT_PATH)
    private static final String OSGI_DEFAULT_CONTEXT_PATH = "default.contextPath";

    @Property(label = "Default Remote Prefix", value = RecapConstants.DEFAULT_DEFAULT_PREFIX)
    private static final String OSGI_DEFAULT_PREFIX = "default.prefix";

    @Property(label = "Default Batch Size", intValue = RecapConstants.DEFAULT_DEFAULT_BATCH_SIZE)
    private static final String OSGI_DEFAULT_BATCH_SIZE = "default.batchSize";

    @Property(label = "Default Batch Read Config", value= RecapConstants.DEFAULT_DEFAULT_BATCH_READ_CONFIG)
    private static final String OSGI_DEFAULT_BATCH_READ_CONFIG = "default.batchReadConfig";

    @Property(label = "Default Last Modified Property", value = RecapConstants.DEFAULT_DEFAULT_LAST_MODIFIED_PROPERTY)
    private static final String OSGI_DEFAULT_LAST_MODIFIED_PROPERTY = "default.lastModifiedProperty";

    private int defaultPort;
    private String defaultContextPath;
    private String defaultPrefix;
    private String defaultUsername;
    private String defaultPassword;
    private int defaultBatchSize;
    private String defaultBatchReadConfig;
    private String defaultLastModifiedProperty;

    boolean sessionsInterrupted = false;

    @Activate
    protected void activate(ComponentContext ctx) {
        Dictionary<?, ?> props = ctx.getProperties();
        defaultPort = OsgiUtil.toInteger(props.get(OSGI_DEFAULT_PORT), RecapConstants.DEFAULT_DEFAULT_PORT);
        defaultContextPath = OsgiUtil.toString(props.get(OSGI_DEFAULT_CONTEXT_PATH), RecapConstants.DEFAULT_DEFAULT_CONTEXT_PATH);
        defaultPrefix = OsgiUtil.toString(props.get(OSGI_DEFAULT_PREFIX), RecapConstants.DEFAULT_DEFAULT_PREFIX);
        defaultUsername = OsgiUtil.toString(props.get(OSGI_DEFAULT_USERNAME), RecapConstants.DEFAULT_DEFAULT_USERNAME);
        defaultPassword = OsgiUtil.toString(props.get(OSGI_DEFAULT_PASSWORD), RecapConstants.DEFAULT_DEFAULT_PASSWORD);
        defaultBatchSize = OsgiUtil.toInteger(props.get(OSGI_DEFAULT_BATCH_SIZE), RecapConstants.DEFAULT_DEFAULT_BATCH_SIZE);
        defaultBatchReadConfig = OsgiUtil.toString(props.get(OSGI_DEFAULT_BATCH_READ_CONFIG), RecapConstants.DEFAULT_DEFAULT_BATCH_READ_CONFIG);
        defaultLastModifiedProperty = OsgiUtil.toString(props.get(OSGI_DEFAULT_LAST_MODIFIED_PROPERTY), RecapConstants.DEFAULT_DEFAULT_LAST_MODIFIED_PROPERTY);
        this.sessionsInterrupted = false;
    }

    @Deactivate
    protected void deactivate(ComponentContext ctx) {
        this.sessionsInterrupted = true;
        defaultPort = 0;
        defaultContextPath = null;
        defaultPrefix = null;
        defaultUsername = null;
        defaultPassword = null;
        defaultBatchSize = 0;
        defaultBatchReadConfig = null;
        defaultLastModifiedProperty = null;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public String getDefaultUsername() {
        return defaultUsername;
    }

    public String getDefaultPassword() {
        return defaultPassword;
    }

    public int getDefaultBatchSize() {
        return defaultBatchSize;
    }

    public String getDefaultBatchReadConfig() {
        return defaultBatchReadConfig;
    }

    public String getDefaultLastModifiedProperty() {
        return defaultLastModifiedProperty;
    }

    public String getDefaultContextPath() {
        return defaultContextPath;
    }

    public String getDefaultPrefix() {
        return defaultPrefix;
    }

    public RecapSession initSession(Session localJcrSession,
                                    RecapAddress address,
                                    RecapOptions options)
            throws RecapSessionException {

        if (address == null) {
            throw new NullPointerException("address");
        }

        if (StringUtils.isEmpty(address.getHostname())) {
            throw new IllegalArgumentException("address.getHostname() must not be empty");
        }

        RecapAddress addr = applyAddressDefaults(address);
        RecapOptions opts = applyOptionsDefaults(options);
        LOGGER.debug("[initSession] opts={}", opts);
        Session srcSession;

        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
            Repository srcRepo = this.getRepository(addr, opts.getBatchReadConfig());
            srcSession = srcRepo.login(
                    new SimpleCredentials(addr.getUsername(),
                            addr.getPassword().toCharArray()));
        } catch (Exception e) {
            throw new RecapSessionException("Failed to login to source repository.", e);
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }

        return new RecapSessionImpl(this, addr, opts, localJcrSession, srcSession);
    }

    private Repository getRepository(RecapAddress recapAddress, BatchReadConfig batchReadConfig) throws RepositoryException {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put(Spi2davexRepositoryServiceFactory.PARAM_REPOSITORY_URI, getRepositoryUrl(recapAddress));
        if (batchReadConfig != null) {
            params.put(Spi2davexRepositoryServiceFactory.PARAM_BATCHREAD_CONFIG, batchReadConfig);
        }
        params.put(Jcr2spiRepositoryFactory.PARAM_REPOSITORY_SERVICE_FACTORY, Spi2davexRepositoryServiceFactory.class.getName());
        params.put(Jcr2spiRepositoryFactory.PARAM_ITEM_CACHE_SIZE, 128);
        params.put(Jcr2spiRepositoryFactory.PARAM_LOG_WRITER_PROVIDER, new Slf4jLogWriterProvider());

        LOGGER.error("[getRepository] repository SPI params: {}", params);
        return new RepositoryFactoryImpl().getRepository(params);
    }

    private RecapAddress applyAddressDefaults(final RecapAddress address) {
        RecapAddressImpl dAddress= new RecapAddressImpl();

        dAddress.setPort(defaultPort);
        dAddress.setUsername(defaultUsername);
        dAddress.setPassword(defaultPassword);
        dAddress.setContextPath(defaultContextPath);
        dAddress.setPrefix(defaultPrefix);

        if (address != null) {

            dAddress.setHostname(address.getHostname());
            dAddress.setHttps(address.isHttps());

            if (address.getPort() != null) {
                dAddress.setPort(address.getPort());
            }
            if (address.getUsername() != null) {
                dAddress.setUsername(address.getUsername());
            }
            if (address.getPassword() != null) {
                dAddress.setPassword(address.getPassword());
            }
            if (address.getContextPath() != null) {
                dAddress.setContextPath(address.getContextPath());
            }
            if (address.getPrefix() != null) {
                dAddress.setPrefix(address.getPrefix());
            }
        }

        return dAddress;
    }

    private RecapOptions applyOptionsDefaults(final RecapOptions options) {
        RecapOptionsImpl dOptions = new RecapOptionsImpl();
        dOptions.setThrottle(0L);
        dOptions.setBatchSize(defaultBatchSize);
        dOptions.setLastModifiedProperty(defaultLastModifiedProperty);
        if (defaultBatchReadConfig != null) {
            dOptions.setBatchReadConfig(RecapBatchReadConfig.parseParameterValue(defaultBatchReadConfig));
        }

        if (options != null) {
            dOptions.setUpdate(options.isUpdate());
            dOptions.setOnlyNewer(options.isOnlyNewer());
            dOptions.setReverse(options.isReverse());
            if (options.getThrottle() != null) {
                dOptions.setThrottle(options.getThrottle());
            }
            if (options.getBatchSize() != null) {
                dOptions.setBatchSize(options.getBatchSize());
            }
            if (options.getLastModifiedProperty() != null) {
                dOptions.setLastModifiedProperty(options.getLastModifiedProperty());
            }
            if (options.getBatchReadConfig() != null) {
                dOptions.setBatchReadConfig(options.getBatchReadConfig());
            }
        }

        return dOptions;
    }

    public String getDisplayableUrl(RecapAddress address) {
        RecapAddress recapAddress = applyAddressDefaults(address);
        StringBuilder addressBuilder = new StringBuilder();
        if (recapAddress.getHostname() != null) {
            addressBuilder.append((recapAddress.isHttps() ? "https://" : "http://"));
            addressBuilder.append(recapAddress.getHostname());
            if (recapAddress.getPort() != null && recapAddress.getPort() != 80) {
                addressBuilder.append(":").append(recapAddress.getPort());
            }
            if (StringUtils.isNotEmpty(recapAddress.getContextPath()) &&
                    !"/".equals(recapAddress.getContextPath())) {
                addressBuilder.append(recapAddress.getContextPath());
            }
        }
        return addressBuilder.toString();
    }

    public String getRepositoryUrl(RecapAddress recapAddress) {
        String base = getDisplayableUrl(recapAddress);
        if (StringUtils.isNotEmpty(base)) {
            if (recapAddress.getPrefix() != null) {
                return getDisplayableUrl(recapAddress) + recapAddress.getPrefix();
            } else {
                return getDisplayableUrl(recapAddress) + "/";
            }
        } else {
            return null;
        }
    }
}