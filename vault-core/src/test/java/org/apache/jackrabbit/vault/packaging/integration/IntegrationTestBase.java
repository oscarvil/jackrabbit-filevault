/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jackrabbit.vault.packaging.integration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.jackrabbit.core.RepositoryImpl;
import org.apache.jackrabbit.core.config.RepositoryConfig;
import org.apache.jackrabbit.core.security.principal.EveryonePrincipal;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.security.SecurityProviderImpl;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.authorization.AuthorizationConfiguration;
import org.apache.jackrabbit.oak.spi.security.privilege.PrivilegeConstants;
import org.apache.jackrabbit.oak.spi.security.user.UserConfiguration;
import org.apache.jackrabbit.oak.spi.security.user.UserConstants;
import org.apache.jackrabbit.oak.spi.security.user.action.AccessControlAction;
import org.apache.jackrabbit.oak.spi.xml.ImportBehavior;
import org.apache.jackrabbit.oak.spi.xml.ProtectedItemImporter;
import org.apache.jackrabbit.vault.fs.api.ProgressTrackerListener;
import org.apache.jackrabbit.vault.fs.io.ImportOptions;
import org.apache.jackrabbit.vault.packaging.JcrPackageManager;
import org.apache.jackrabbit.vault.packaging.impl.JcrPackageManagerImpl;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <code>IntegrationTestBase</code>...
 */
public class IntegrationTestBase  {

    /**
     * default logger
     */
    private static final Logger log = LoggerFactory.getLogger(IntegrationTestBase.class);

    private static final String REPO_HOME = "target/repository";

    protected static Repository repository;

    protected Session admin;

    protected JcrPackageManager packMgr;

    @BeforeClass
    public static void initRepository() throws RepositoryException {
        if (isOak()) {
            Properties userProps = new Properties();
            userProps.put(UserConstants.PARAM_USER_PATH, "/home/users");
            userProps.put(UserConstants.PARAM_GROUP_PATH, "/home/groups");
            userProps.put(AccessControlAction.USER_PRIVILEGE_NAMES, new String[] {PrivilegeConstants.JCR_ALL});
            userProps.put(AccessControlAction.GROUP_PRIVILEGE_NAMES, new String[] {PrivilegeConstants.JCR_READ});
            userProps.put(ProtectedItemImporter.PARAM_IMPORT_BEHAVIOR, ImportBehavior.NAME_BESTEFFORT);
            Properties authzProps = new Properties();
            authzProps.put(ProtectedItemImporter.PARAM_IMPORT_BEHAVIOR, ImportBehavior.NAME_BESTEFFORT);
            Properties securityProps = new Properties();
            securityProps.put(UserConfiguration.NAME, ConfigurationParameters.of(userProps));
            securityProps.put(AuthorizationConfiguration.NAME, ConfigurationParameters.of(authzProps));
            repository = new Jcr()
                    .with(new SecurityProviderImpl(ConfigurationParameters.of(securityProps)))
                    .createRepository();

            // setup default read ACL for everyone
            Session admin = repository.login(new SimpleCredentials("admin", "admin".toCharArray()));
            AccessControlUtils.addAccessControlEntry(admin, "/", EveryonePrincipal.getInstance(), new String[]{"jcr:read"}, true);
            admin.save();
            admin.logout();
        } else {
            InputStream in = IntegrationTestBase.class.getResourceAsStream("repository.xml");
            RepositoryConfig cfg = RepositoryConfig.create(in, REPO_HOME);
            repository = RepositoryImpl.create(cfg);
        }
        log.info("repository created: {} {}",
                repository.getDescriptor(Repository.REP_NAME_DESC),
                repository.getDescriptor(Repository.REP_VERSION_DESC));
    }

    @AfterClass
    public static void shutdownRepository() {
        if (repository instanceof RepositoryImpl) {
            ((RepositoryImpl) repository).shutdown();
        }
        repository = null;
    }

    @Before
    public void setUp() throws Exception {
        admin = repository.login(new SimpleCredentials("admin", "admin".toCharArray()));

        // ensure not packages or tmp
        clean("/etc");
        clean("/tmp");
        clean("/testroot");

        packMgr = new JcrPackageManagerImpl(admin);
    }

    public static boolean isOak() {
        return Boolean.getBoolean("oak");
    }

    public void clean(String path) {
        try {
            admin.getNode(path).remove();
            admin.save();
        } catch (RepositoryException e) {
            // ignore
        }
    }
    @After
    public void tearDown() throws Exception {
        packMgr = null;
        if (admin != null) {
            admin.logout();
            admin = null;
        }
    }

    public InputStream getStream(String name) {
        InputStream in;
        if (name.startsWith("/")) {
           in = getClass().getClassLoader().getResourceAsStream(name);
        } else {
            in = getClass().getResourceAsStream(name);
        }
        return in;
    }

    public File getTempFile(String name) throws IOException {
        InputStream in = getStream(name);

        File tmpFile = File.createTempFile("vaultpack", ".zip");
        FileOutputStream out = FileUtils.openOutputStream(tmpFile);
        IOUtils.copy(in, out);
        in.close();
        out.close();
        return tmpFile;
    }


    public ImportOptions getDefaultOptions() {
        ImportOptions opts = new ImportOptions();
        opts.setListener(new ProgressTrackerListener() {
            public void onMessage(Mode mode, String action, String path) {
                log.info("{} {}", action, path);
            }

            public void onError(Mode mode, String path, Exception e) {
                log.info("E {} {}", path, e.toString());
            }
        });
        return opts;
    }

    public void assertNodeExists(String path) throws RepositoryException {
        assertTrue(path + " should exist", admin.nodeExists(path));
    }

    public void assertNodeMissing(String path) throws RepositoryException {
        assertFalse(path + " should not exist", admin.nodeExists(path));
    }

    public void assertProperty(String path, String value) throws RepositoryException {
        assertEquals(path + " should contain " + value, value, admin.getProperty(path).getString());
    }

    public void createNodes(Node parent, int maxDepth, int nodesPerFolder) throws RepositoryException {
        for (int i=0; i<nodesPerFolder; i++) {
            Node n = parent.addNode("n" + i, "nt:folder");
            if (maxDepth > 0) {
                createNodes(n, maxDepth - 1, nodesPerFolder);
            }
        }
    }

    public int countNodes(Node parent) throws RepositoryException {
        int total = 1;
        NodeIterator iter = parent.getNodes();
        while (iter.hasNext()) {
            total += countNodes(iter.nextNode());
        }
        return total;
    }
}