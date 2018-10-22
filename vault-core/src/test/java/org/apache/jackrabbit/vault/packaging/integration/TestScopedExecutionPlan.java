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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.jcr.RepositoryException;

import org.apache.jackrabbit.vault.packaging.CyclicDependencyException;
import org.apache.jackrabbit.vault.packaging.DependencyException;
import org.apache.jackrabbit.vault.packaging.PackageException;
import org.apache.jackrabbit.vault.packaging.PackageId;
import org.apache.jackrabbit.vault.packaging.PackageType;
import org.apache.jackrabbit.vault.packaging.registry.ExecutionPlan;
import org.apache.jackrabbit.vault.packaging.registry.ExecutionPlanBuilder;
import org.apache.jackrabbit.vault.packaging.registry.PackageRegistry;
import org.apache.jackrabbit.vault.packaging.registry.PackageTask;
import org.apache.jackrabbit.vault.packaging.registry.ScopeHandler;
import org.apache.jackrabbit.vault.packaging.registry.impl.JcrPackageRegistry;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test the Package registry interface
 */
public class TestScopedExecutionPlan extends IntegrationTestBase {



    private static String TEST_PACKAGE_MIXED = "testpackages/mixed_package.zip";

    private PackageRegistry registry;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        registry = new JcrPackageRegistry(admin);
        clean("/libs");
        clean("/tmp");
    }

    @Test
    public void testMixedScopeInstall() throws IOException, PackageException, RepositoryException {
        assertNodeMissing("/libs/foo");
        assertNodeMissing("/tmp/foo");
        
        PackageId idA = registry.register(getStream(TEST_PACKAGE_MIXED), false);
       
        assertFalse("package is registered", registry.open(idA).isInstalled());

        ExecutionPlanBuilder builder = registry.createExecutionPlan()
                .addTask().with(idA).with(PackageTask.Type.INSTALL)
                .with(admin)
                .with(getDefaultOptions().getListener());
        ScopeHandler sh = builder.setScope(PackageType.MIXED);
        ExecutionPlan plan  = builder.execute();
        assertTrue("plan is finished", plan.isExecuted());
        assertFalse("plan has no errors", plan.hasErrors());

        assertTrue("package is installed", registry.open(idA).isInstalled());
        
        assertNodeExists("/tmp/foo");
        assertNodeExists("/libs/foo");
        
        assertTrue(sh.getPackagesLeavingScope().isEmpty());
    }

    @Test
    public void testApplicationScopeInstall() throws IOException, PackageException, RepositoryException {
        assertNodeMissing("/libs/foo");
        assertNodeMissing("/tmp/foo");
        PackageId idA = registry.register(getStream(TEST_PACKAGE_MIXED), false);
       
        assertFalse("package is registered", registry.open(idA).isInstalled());

        ExecutionPlanBuilder builder = registry.createExecutionPlan()
                .addTask().with(idA).with(PackageTask.Type.INSTALL)
                .with(admin)
                .with(getDefaultOptions().getListener());
        ScopeHandler sh = builder.setScope(PackageType.APPLICATION);
        ExecutionPlan plan  = builder.execute();
        assertTrue("plan is finished", plan.isExecuted());
        assertFalse("plan has no errors", plan.hasErrors());

        assertTrue("package is installed", registry.open(idA).isInstalled());
        
        assertNodeMissing("/tmp/foo");
        assertNodeExists("/libs/foo");
        
        assertEquals(1, sh.getPackagesLeavingScope().size());
        assertEquals(idA, sh.getPackagesLeavingScope().get(0));
    }

}