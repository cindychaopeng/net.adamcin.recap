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

import net.adamcin.recap.api.RecapAddress;
import net.adamcin.recap.api.RecapOptions;
import net.adamcin.recap.api.RecapProgressListener;
import net.adamcin.recap.api.RecapSession;
import net.adamcin.recap.api.RecapSessionException;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import javax.jcr.NamespaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author madamcin
 * @version $Id: RecapSessionImpl.java$
 */
public class RecapSessionImpl implements RecapSession {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecapSessionImpl.class);

    private final RecapImpl recap;
    private final RecapAddress address;
    private final RecapOptions options;
    private final Session localSession;
    private final Session remoteSession;

    private RecapProgressListener progressListener;

    private String lastSuccessfulPath;
    private int totalRecapPaths = 0;
    private int numNodes = 0;
    private int totalNodes = 0;
    private long totalSize = 0L;
    private long currentSize = 0L;
    private long start = 0L;
    private long end = 0L;

    private Map<String, String> prefixMapping = new HashMap<String, String>();
    private boolean allowLastModifiedProperty = false;
    private boolean interrupted = false;
    private boolean finished = false;


    public RecapSessionImpl(RecapImpl recap,
                            RecapAddress address,
                            RecapOptions options,
                            Session localSession,
                            Session remoteSession)
            throws RecapSessionException {

        this.recap = recap;
        this.address = address;
        this.options = options;
        this.localSession = localSession;
        this.remoteSession = remoteSession;

        allowLastModifiedProperty = isValidNameForSession(this.options.getLastModifiedProperty());
    }

    public RecapAddress getAddress() {
        return address;
    }

    public RecapOptions getOptions() {
        return options;
    }

    public boolean isFinished() {
        return finished;
    }

    public void logout() {
        if (this.remoteSession != null) {
            this.remoteSession.logout();
        }
    }

    public RecapProgressListener getProgressListener() {
        return progressListener;
    }

    public void setProgressListener(RecapProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    private void trackPath(RecapProgressListener.PathAction action, String path) {
        if (this.getProgressListener() != null) {
            this.getProgressListener().onPath(action, this.totalNodes, path);
        }
    }

    private void trackError(String path, Exception exception) {
        if (this.getProgressListener() != null) {
            this.getProgressListener().onError(path, exception);
        }
    }

    private void trackFailure(String path, Exception exception) {
        if (this.getProgressListener() != null) {
            this.getProgressListener().onFailure(path, exception);
        }
    }

    private void trackMessage(String fmt, Object... args) {
        if (this.getProgressListener() != null) {
            this.getProgressListener().onMessage(fmt, args);
        }
    }

    private Session getSourceSession() {
        if (this.options.isReverse()) {
            return this.localSession;
        } else {
            return this.remoteSession;
        }
    }

    private Session getTargetSession() {
        if (this.options.isReverse()) {
            return this.remoteSession;
        } else {
            return this.localSession;
        }
    }
    public Node getOrCreateTargetNode(Node sourceNode) throws RepositoryException {
        if (sourceNode.getDepth() == 0) {
            return getTargetSession().getRootNode();
        } else if (getTargetSession().getRootNode().hasNode(sourceNode.getPath().substring(1))) {
            return getTargetSession().getRootNode().getNode(sourceNode.getPath().substring(1));
        } else {
            Node sourceParent = sourceNode.getParent();
            Node targetParent = getOrCreateTargetNode(sourceParent);
            Node targetNode;
            if (!targetParent.hasNode(sourceNode.getName())) {
                targetNode = targetParent.addNode(sourceNode.getName(), sourceNode.getPrimaryNodeType().getName());
                trackPath(RecapProgressListener.PathAction.ADD, targetNode.getPath());
            } else {
                targetNode = targetParent.getNode(sourceNode.getName());
            }
            processBatch();
            return targetNode;
        }
    }

    public void syncPath(String rootPath) throws RecapSessionException {
        if (this.finished) {
            throw new RecapSessionException("RecapSession already finished.");
        }

        trackMessage("Copy %s %s http://%s:%d/", rootPath, this.options.isReverse() ? "to" : "from", this.address.getHostname(), this.address.getPort());

        try {
            if (this.start == 0L) {
                this.start = System.currentTimeMillis();
            }

            Node srcNode = getSourceSession().getNode(rootPath);
            Node srcParent = srcNode.getParent();
            Node dstParent = getOrCreateTargetNode(srcParent);

            String dstName = srcNode.getName();

            this.copy(srcNode, dstParent, dstName, !this.options.isNoRecurse());
            this.lastSuccessfulPath = rootPath;
            this.totalRecapPaths++;
        } catch (PathNotFoundException e) {
            LOGGER.debug("PathNotFoundException while preparing path: {}. Message: {}", rootPath, e.getMessage());
            trackError(rootPath, e);
        } catch (RepositoryException e) {
            LOGGER.error("RepositoryException while copying path: {}. Message: {}", rootPath, e.getMessage());
            trackFailure(rootPath, e);
            this.interrupted = true;
            this.finish();
            throw new RecapSessionException("RepositoryException while preparing path: " + rootPath, e);
        } catch (RecapSessionException e) {

        }
    }

    public void finish() throws RecapSessionException {
        this.finished = true;
        RecapSessionException exception = null;
        if (!this.interrupted && this.numNodes > 0) {
            trackMessage("Saving %d nodes...", this.numNodes);
            try {
                getTargetSession().save();
                this.numNodes = 0;
                this.currentSize = 0L;
                trackMessage("Done.");
            } catch (RepositoryException e) {
                LOGGER.error("[finish] Failed to save remaining changes.", e);
                trackMessage("Failed to save remaining changes. %s", e.getMessage());
                this.interrupted = true;
                exception = new RecapSessionException("Failed to save remaining changes.", e);
            }
        }

        this.end = System.currentTimeMillis();
        this.logout();

        if (this.getTotalNodes() > 0) {

            trackMessage("Copy %s. %d nodes in %dms. %d bytes",
                            (this.interrupted ? "interrupted" : "completed"),
                            this.getTotalNodes(), this.getTotalTimeMillis(), this.getTotalSize());

            trackMessage("%d root paths added or updated successfully. Last successful path: %s",
                    this.getTotalRecapPaths(), this.getLastSuccessfulRecapPath());
        }

        if (exception != null) {
            throw exception;
        }
    }

    private void processBatch() throws RepositoryException {
        if (++this.numNodes >= this.getOptions().getBatchSize()) {
            trackMessage("Intermediate saving %d nodes (%d kB)...", this.numNodes, this.currentSize / 1000L);
            long now = System.currentTimeMillis();
            getTargetSession().save();
            long end = System.currentTimeMillis();
            trackMessage("Done in %d ms. Total time: %d, total nodes %d, %d kB", end - now, end - this.start,
                    this.totalNodes, this.totalSize / 1000L);
            this.numNodes = 0;
            this.currentSize = 0L;
            if (this.options.getThrottle() > 0L) {
                trackMessage("Throttling enabled. Waiting %d second%s...", this.options.getThrottle(), this.options.getThrottle() == 1L ? "" : "s");
                try {
                    Thread.sleep(this.options.getThrottle() * 1000L);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private void copy(Node src, Node dstParent, String dstName, boolean recursive) throws RecapSessionException, RepositoryException {
        if (recap.sessionsInterrupted) {
            throw new RecapSessionException("RecapSession interrupted.");
        }

        String path = src.getPath();
        String dstPath = dstParent.getPath() + "/" + dstName;

        boolean useSysView = src.getDefinition().isProtected();

        boolean isNew = false;
        boolean overwrite = this.options.isUpdate();
        ++totalNodes;
        Node dst;
        if (dstParent.hasNode(dstName)) {
            dst = dstParent.getNode(dstName);
            if (overwrite) {
                if ((this.options.isOnlyNewer()) && (dstName.equals(JcrConstants.JCR_CONTENT))) {
                    if (isNewer(src, dst)) {
                        trackPath(RecapProgressListener.PathAction.UPDATE, dstPath);
                    } else {
                        overwrite = false;
                        recursive = false;
                        trackPath(RecapProgressListener.PathAction.NO_ACTION, dstPath);
                    }
                } else {
                    trackPath(RecapProgressListener.PathAction.UPDATE, dstPath);
                }

                if (useSysView) {
                    dst = sysCopy(src, dstParent, dstName);
                }
            } else {
                trackPath(RecapProgressListener.PathAction.NO_ACTION, dstPath);
            }
        } else {
            try {
                if (useSysView) {
                    dst = sysCopy(src, dstParent, dstName);
                } else {
                    dst = dstParent.addNode(dstName, src.getPrimaryNodeType().getName());
                }
                trackPath(RecapProgressListener.PathAction.ADD, dstPath);
                isNew = true;
            } catch (RepositoryException e) {
                LOGGER.warn("Error while adding node {} (ignored): {}", dstPath, e.toString());
                trackError(dstPath, e);
                return;
            }
        }
        if (useSysView) {
            trackTree(dst, isNew);
        } else {
            Set<String> names = new HashSet<String>();
            if ((overwrite) || (isNew)) {
                if (!isNew) {
                    for (NodeType nt : dst.getMixinNodeTypes()) {
                        names.add(nt.getName());
                    }

                    for (NodeType nt : src.getMixinNodeTypes()) {
                        String mixName = checkNameSpace(nt.getName());
                        if (!names.remove(mixName)) {
                            dst.addMixin(nt.getName());
                        }
                    }

                    for (String mix : names) {
                        dst.removeMixin(mix);
                    }
                } else {
                    for (NodeType nt : src.getMixinNodeTypes()) {
                        dst.addMixin(checkNameSpace(nt.getName()));
                    }
                }

                names.clear();
                if (!isNew) {
                    PropertyIterator iter = dst.getProperties();
                    while (iter.hasNext()) {
                        names.add(checkNameSpace(iter.nextProperty().getName()));
                    }
                }
                PropertyIterator iter = src.getProperties();
                while (iter.hasNext()) {
                    Property p = iter.nextProperty();
                    String pName = checkNameSpace(p.getName());
                    names.remove(pName);

                    if (p.getDefinition().isProtected()) {
                        continue;
                    }
                    if (dst.hasProperty(pName)) {
                        dst.getProperty(pName).remove();
                    }
                    if (p.getDefinition().isMultiple()) {
                        Value[] vs = p.getValues();
                        dst.setProperty(pName, vs);
                        for (long s : p.getLengths()) {
                            this.totalSize += s;
                            this.currentSize += s;
                        }
                    } else {
                        Value v = p.getValue();
                        dst.setProperty(pName, v);
                        long s = p.getLength();
                        this.totalSize += s;
                        this.currentSize += s;
                    }
                }

                for (String pName : names) {
                    try {
                        dst.getProperty(pName).remove();
                    } catch (RepositoryException e) {
                    }
                }
            }

            if (recursive) {
                names.clear();
                if ((overwrite) && (!isNew)) {
                    NodeIterator niter = dst.getNodes();
                    while (niter.hasNext()) {
                        names.add(checkNameSpace(niter.nextNode().getName()));
                    }
                }
                NodeIterator niter = src.getNodes();
                while (niter.hasNext()) {
                    Node child = niter.nextNode();
                    String cName = checkNameSpace(child.getName());
                    names.remove(cName);
                    copy(child, dst, cName, true);
                }

                for (String name : names) {
                    try {
                        Node cNode = dst.getNode(name);
                        trackPath(RecapProgressListener.PathAction.DELETE, cNode.getPath());
                        cNode.remove();
                    } catch (RepositoryException e) {
                    }
                }
            }
        }
        processBatch();
    }

    private Node sysCopy(Node src, Node dstParent, String dstName)
            throws RepositoryException {
        try {
            ContentHandler handler = dstParent.getSession().getImportContentHandler(dstParent.getPath(), 0);
            src.getSession().exportSystemView(src.getPath(), handler, true, false);
            return dstParent.getNode(dstName);
        } catch (SAXException e) {
            throw new RepositoryException("Unable to perform sysview copy", e);
        }
    }

    private void trackTree(Node node, boolean isNew) throws RepositoryException {
        NodeIterator iter = node.getNodes();
        while (iter.hasNext()) {
            Node child = iter.nextNode();
            if (isNew) {
                trackPath(RecapProgressListener.PathAction.ADD, child.getPath());
            } else {
                trackPath(RecapProgressListener.PathAction.UPDATE, child.getPath());
            }
            trackTree(child, isNew);
        }
    }

    private boolean isNewer(Node src, Node dst) {
        try {
            Calendar srcDate = null;
            Calendar dstDate = null;
            if ((this.allowLastModifiedProperty) && (src.hasProperty(this.options.getLastModifiedProperty())) && (dst.hasProperty(this.options.getLastModifiedProperty()))) {
                srcDate = src.getProperty(this.options.getLastModifiedProperty()).getDate();
                dstDate = dst.getProperty(this.options.getLastModifiedProperty()).getDate();
            } else if ((src.hasProperty(JcrConstants.JCR_LASTMODIFIED)) && (dst.hasProperty(JcrConstants.JCR_LASTMODIFIED))) {
                srcDate = src.getProperty(JcrConstants.JCR_LASTMODIFIED).getDate();
                dstDate = dst.getProperty(JcrConstants.JCR_LASTMODIFIED).getDate();
            }
            return (srcDate == null) || (dstDate == null) || (srcDate.after(dstDate));
        } catch (RepositoryException e) {
            LOGGER.error("Unable to compare dates: {}", e.toString());
        }
        return true;
    }

    private boolean isValidNameForSession(String name) {
        if (StringUtils.isEmpty(name)) {
            return false;
        }

        try {
            mapPrefixedName(name);
            return true;
        } catch (RepositoryException e) {
            LOGGER.error("Error processing namespace for {}: {}", name, e.toString());
            return false;
        }
    }

    private String mapPrefixedName(String name) throws RepositoryException {
        int idx = name.indexOf(':');
        if (idx > 0) {
            String prefix = name.substring(0, idx);
            String mapped = this.prefixMapping.get(prefix);
            if (mapped == null) {
                String uri = getSourceSession().getNamespaceURI(prefix);
                int i = -1;
                try {
                    mapped = getTargetSession().getNamespacePrefix(uri);
                } catch (NamespaceException e) {
                    mapped = prefix;
                    i = 0;
                }

                while (i >= 0) {
                    try {
                        getTargetSession().getWorkspace().getNamespaceRegistry().registerNamespace(mapped, uri);
                        i = -1;
                    } catch (NamespaceException e1) {
                        mapped = prefix + i++;
                    }
                }

                this.prefixMapping.put(prefix, mapped);
            }
            if (mapped.equals(prefix)) {
                return name;
            }
            return mapped + prefix.substring(idx);
        }

        return name;
    }

    private String checkNameSpace(String name) {
        try {
            name = mapPrefixedName(name);
        } catch (RepositoryException e) {
            LOGGER.error("Error processing namespace for {}: {}", name, e.toString());
        }
        return name;
    }

    public Session getRemoteSession() {
        return this.remoteSession;
    }

    public Session getLocalSession() {
        return this.localSession;
    }

    public int getTotalRecapPaths() {
        return this.totalRecapPaths;
    }

    public String getLastSuccessfulRecapPath() {
        return this.lastSuccessfulPath;
    }

    public int getTotalNodes() {
        return this.totalNodes;
    }

    public long getTotalSize() {
        return this.totalSize;
    }

    public long getTotalTimeMillis() {
        return this.end - this.start;
    }

}