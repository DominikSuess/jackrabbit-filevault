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

package org.apache.jackrabbit.vault.packaging.registry.impl;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.jackrabbit.vault.fs.api.ProgressTrackerListener;
import org.apache.jackrabbit.vault.util.Text;

public class ScopeTracker implements ProgressTrackerListener {

    private static final String[] APP_ROOTS = {"/apps", "/libs" };

    private final ProgressTrackerListener base;

    private final String[] roots;

    private int numMatches = 0;

    private int numMisses = 0;

    private boolean matches;

    public ScopeTracker(@Nonnull String[] roots, boolean matches, @Nullable ProgressTrackerListener base) {
        this.base = base;
        this.roots = roots;
        this.matches = matches;
    }

    public static ScopeTracker createApplicationScoped(@Nullable ProgressTrackerListener base) {
        return new ScopeTracker(APP_ROOTS, true, base);
    }
    
    public static ScopeTracker createContentScoped(@Nullable ProgressTrackerListener base) {
        return new ScopeTracker(APP_ROOTS, false, base);
    }

    @Override
    public void onMessage(Mode mode, String action, String path) {
        if (base != null) {
            base.onMessage(mode, action, path);
        }
        if (mode == Mode.PATHS && "A".equals(action)) {
            if (matches ? match(path) : !match(path)) {
                numMatches++;
            } else {
                numMisses++;
            }
        }
    }

    @Override
    public void onError(Mode mode, String path, Exception e) {
        if (base != null) {
            base.onError(mode, path, e);
        }
    }

    public int getNumMatches() {
        return numMatches;
    }

    public int getNumMisses() {
        return numMisses;
    }

    private boolean match(String path) {
        for (String root: roots) {
            if (Text.isDescendantOrEqual(root, path)) {
                return true;
            }
        }
        return false;
    }


}