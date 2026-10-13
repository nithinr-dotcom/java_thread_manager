package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.BranchInfoQuery;

public final class BranchInfoService implements BranchInfoQuery {

    private final BranchContext branch;

    public BranchInfoService(BranchContext branch) {
        this.branch = branch;
    }

    @Override
    public BranchInfo branch() {
        return new BranchInfo(branch.branchCode(), branch.currency(), branch.zone());
    }
}
