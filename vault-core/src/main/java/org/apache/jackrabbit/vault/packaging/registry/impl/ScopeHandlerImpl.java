package org.apache.jackrabbit.vault.packaging.registry.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.jackrabbit.vault.fs.config.DefaultWorkspaceFilter;
import org.apache.jackrabbit.vault.fs.io.ImportOptions;
import org.apache.jackrabbit.vault.packaging.PackageId;
import org.apache.jackrabbit.vault.packaging.PackageType;
import org.apache.jackrabbit.vault.packaging.ScopedWorkspaceFilter;
import org.apache.jackrabbit.vault.packaging.registry.RegisteredPackage;
import org.apache.jackrabbit.vault.packaging.registry.ScopeHandler;

public class ScopeHandlerImpl implements ScopeHandler {
    
    Map<PackageId, ScopeTracker> trackers = new HashMap<>();
    private PackageType type = PackageType.MIXED;

    public void decorateOpts(ImportOptions opts, RegisteredPackage pkg) {
        if (PackageType.APPLICATION.equals(type) || PackageType.CONTENT.equals(type)) {
            ScopeTracker scopeTracker;
            ScopedWorkspaceFilter scopedFilter;
            if (PackageType.APPLICATION.equals(type)) {
                scopedFilter = ScopedWorkspaceFilter.createApplicationScoped((DefaultWorkspaceFilter) pkg.getWorkspaceFilter());
                scopeTracker = ScopeTracker.createApplicationScoped(opts.getListener());
            }
            else {
                scopedFilter = ScopedWorkspaceFilter.createContentScoped((DefaultWorkspaceFilter) pkg.getWorkspaceFilter());
                scopeTracker = ScopeTracker.createContentScoped(opts.getListener());
            }
            trackers.put(pkg.getId(), scopeTracker);
            opts.setListener(scopeTracker);
            opts.setFilter(scopedFilter);
        }
    }

    @Override
    public List<PackageId> getPackagesLeavingScope() {
        List<PackageId> packagesLeavingScope = new ArrayList<>();
        for (Entry<PackageId, ScopeTracker> entry : trackers.entrySet()) {
            if(entry.getValue().getNumMisses() > 0) {
               packagesLeavingScope.add(entry.getKey());
            }
        }
        return packagesLeavingScope;
    }

    public void setScope(PackageType type) {
        this.type = type;
    }

}
