package me.cortex.voxy.client.sodium.provider;

import me.cortex.voxy.common.voxelization.VoxelizedSection;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class VoxyFarTerrainProvider {
    public static final String PASS_NO_GAP = "PASS_SODIUM_COMPATIBLE_PROVIDER_NO_GAP";
    public static final String PASS_PROVIDER_RENDER_AUTHORITY = "PASS_PROVIDER_RENDER_AUTHORITY";
    public static final String PASS_VISUAL_SOURCE_CORRECTNESS = "PASS_VOXY_VISUAL_SOURCE_CORRECTNESS";
    public static final String FAIL_GAP = "FAIL_PROVIDER_GAP";
    public static final String FAIL_INVALID_RENDERED = "FAIL_PROVIDER_INVALID_RENDERED";
    public static final String FAIL_OWNERSHIP_CONFLICT = "FAIL_PROVIDER_OWNERSHIP_CONFLICT";
    public static final String FAIL_UNTRUSTED_SOURCE = "FAIL_PROVIDER_UNTRUSTED_SOURCE";
    public static final String FAIL_UNSUPPORTED_PASS_RENDERED = "FAIL_PROVIDER_UNSUPPORTED_PASS_RENDERED";
    public static final String FAIL_STALE_UPLOAD_RENDERED = "FAIL_PROVIDER_STALE_UPLOAD_RENDERED";
    public static final String FAIL_ORE_LEAK = "FAIL_PROVIDER_ORE_LEAK_SURFACE_REPRESENTATIVE";
    public static final String FAIL_MATERIAL_PARITY = "FAIL_PROVIDER_SODIUM_MATERIAL_PARITY";
    public static final String FAIL_FOG_DEPTH_PARITY = "FAIL_PROVIDER_FOG_DEPTH_PARITY";
    public static final String UNKNOWN_NO_BOUNDARY_REQUESTS = "UNKNOWN_PROVIDER_NO_BOUNDARY_REQUESTS";
    public static final String SKIPPED_INCOMPLETE_IRIS_STATE = "SKIPPED_INCOMPLETE_IRIS_STATE";

    private final VoxySectionInvalidationTracker invalidationTracker = new VoxySectionInvalidationTracker();
    private final VoxyProviderRenderIndex renderIndex = new VoxyProviderRenderIndex();
    private final ConcurrentHashMap<Long, BoundaryCoverageState> boundaryCoverage = new ConcurrentHashMap<>();
    private final AtomicLong syntheticBoundaryKey = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong exactOwnedCells = new AtomicLong();
    private final AtomicLong lodOwnedCells = new AtomicLong();
    private final AtomicLong parentFallbackCells = new AtomicLong();
    private final AtomicLong invalidRejectedCells = new AtomicLong();
    private final AtomicLong parentChildConflictCells = new AtomicLong();
    private final AtomicLong boundaryExactSections = new AtomicLong();
    private final AtomicLong boundaryParentFallbackSections = new AtomicLong();
    private final AtomicLong boundaryRejectedSections = new AtomicLong();
    private final AtomicLong boundaryMissingSections = new AtomicLong();
    private final AtomicLong invalidRenderedSections = new AtomicLong();
    private final AtomicLong boundarySourceRealChunkSections = new AtomicLong();
    private final AtomicLong boundarySourceSurfacePreviewSections = new AtomicLong();
    private final AtomicLong boundarySourceSyntheticPreviewSections = new AtomicLong();
    private final AtomicLong boundarySourceUnknownSections = new AtomicLong();
    private final AtomicLong boundarySourceUntrustedRealSections = new AtomicLong();
    private final AtomicLong boundaryDegradedPreviewSections = new AtomicLong();
    private final AtomicLong unsupportedPassSkips = new AtomicLong();
    private final AtomicLong irisFailClosedSkips = new AtomicLong();
    private final AtomicLong staleUploadRejections = new AtomicLong();
    private final AtomicLong parentSuppressedSections = new AtomicLong();
    private final AtomicLong providerRenderListLength = new AtomicLong();
    private final AtomicLong providerRenderListEpoch = new AtomicLong();
    private final AtomicLong providerRenderListStaleSkips = new AtomicLong();
    private final AtomicLong providerCommandGenerationCount = new AtomicLong();
    private final AtomicLong providerTraversalBypassCount = new AtomicLong();
    private volatile VoxyFarTerrainProviderSnapshot snapshot;
    private volatile VoxyTerrainProviderDiagnostics diagnostics;
    private volatile Set<Long> currentRefreshRenderSections;

    private enum BoundaryCoverageState {
        EXACT,
        FALLBACK,
        REJECTED,
        MISSING
    }

    public VoxyFarTerrainProvider(VoxyFarTerrainProviderSnapshot initialSnapshot) {
        this.snapshot = initialSnapshot;
        this.diagnostics = VoxyTerrainProviderDiagnostics.empty(initialSnapshot);
    }

    public VoxyFarTerrainProviderSnapshot snapshot() {
        return this.snapshot;
    }

    public void updateSnapshot(VoxyFarTerrainProviderSnapshot snapshot) {
        if (!snapshot.equals(this.snapshot)) {
            this.resetRuntimeState(snapshot);
            return;
        }
        this.snapshot = snapshot;
        this.diagnostics = this.diagnostics.withPolicy(snapshot);
    }

    public void resetRuntimeState(VoxyFarTerrainProviderSnapshot snapshot) {
        this.snapshot = snapshot;
        this.invalidationTracker.clear();
        this.renderIndex.clear();
        this.boundaryCoverage.clear();
        this.syntheticBoundaryKey.set(Long.MIN_VALUE);
        this.exactOwnedCells.set(0);
        this.lodOwnedCells.set(0);
        this.parentFallbackCells.set(0);
        this.invalidRejectedCells.set(0);
        this.parentChildConflictCells.set(0);
        this.boundaryExactSections.set(0);
        this.boundaryParentFallbackSections.set(0);
        this.boundaryRejectedSections.set(0);
        this.boundaryMissingSections.set(0);
        this.invalidRenderedSections.set(0);
        this.boundarySourceRealChunkSections.set(0);
        this.boundarySourceSurfacePreviewSections.set(0);
        this.boundarySourceSyntheticPreviewSections.set(0);
        this.boundarySourceUnknownSections.set(0);
        this.boundarySourceUntrustedRealSections.set(0);
        this.boundaryDegradedPreviewSections.set(0);
        this.unsupportedPassSkips.set(0);
        this.irisFailClosedSkips.set(0);
        this.staleUploadRejections.set(0);
        this.parentSuppressedSections.set(0);
        this.providerRenderListLength.set(0);
        this.providerRenderListEpoch.set(0);
        this.providerRenderListStaleSkips.set(0);
        this.providerCommandGenerationCount.set(0);
        this.providerTraversalBypassCount.set(0);
        this.currentRefreshRenderSections = null;
        this.diagnostics = VoxyTerrainProviderDiagnostics.empty(snapshot);
    }

    public VoxyTerrainProviderDiagnostics diagnostics() {
        return this.diagnostics;
    }

    public VoxySectionInvalidationTracker invalidationTracker() {
        return this.invalidationTracker;
    }

    public long invalidateSection(long sectionKey) {
        this.boundaryCoverage.remove(sectionKey);
        this.renderIndex.remove(sectionKey);
        this.refreshBoundaryDiagnostics();
        return this.invalidationTracker.invalidate(sectionKey);
    }

    public void recordBoundaryExactSection() {
        this.recordBoundaryExactSection(this.nextSyntheticBoundaryKey());
    }

    public void recordBoundaryExactSection(long sectionKey) {
        this.boundaryExactSections.incrementAndGet();
        this.recordBoundaryCoverage(sectionKey, BoundaryCoverageState.EXACT);
    }

    public void recordBoundaryParentFallbackSection() {
        this.recordBoundaryParentFallbackSection(this.nextSyntheticBoundaryKey());
    }

    public void recordBoundaryParentFallbackSection(long sectionKey) {
        this.boundaryParentFallbackSections.incrementAndGet();
        this.recordBoundaryCoverage(sectionKey, BoundaryCoverageState.FALLBACK);
    }

    public void recordBoundaryRejectedSection() {
        this.recordBoundaryRejectedSection(this.nextSyntheticBoundaryKey());
    }

    public void recordBoundaryRejectedSection(long sectionKey) {
        this.boundaryRejectedSections.incrementAndGet();
        this.recordBoundaryCoverage(sectionKey, BoundaryCoverageState.REJECTED);
    }

    public void recordBoundaryMissingSection() {
        this.recordBoundaryMissingSection(this.nextSyntheticBoundaryKey());
    }

    public void recordBoundaryMissingSection(long sectionKey) {
        this.boundaryMissingSections.incrementAndGet();
        this.recordBoundaryCoverage(sectionKey, BoundaryCoverageState.MISSING);
    }

    public void recordMaterialRejected(VoxyTerrainFailureReason reason) {
        this.invalidRejectedCells.incrementAndGet();
        if (reason == VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED) {
            this.unsupportedPassSkips.incrementAndGet();
        }
        this.refreshBoundaryDiagnostics();
    }

    public void recordInvalidRenderedSection(VoxyTerrainFailureReason reason) {
        this.invalidRenderedSections.incrementAndGet();
        this.refreshBoundaryDiagnostics();
    }

    public void recordStaleUploadRejection(long sectionKey) {
        this.staleUploadRejections.incrementAndGet();
        this.renderIndex.remove(sectionKey);
        this.boundaryCoverage.remove(sectionKey);
        this.refreshBoundaryDiagnostics();
    }

    public void recordParentSuppressed(long sectionKey) {
        this.parentSuppressedSections.incrementAndGet();
        this.renderIndex.remove(sectionKey);
        this.boundaryCoverage.remove(sectionKey);
        this.refreshBoundaryDiagnostics();
    }

    public void recordBoundarySource(
            VoxelizedSection.SourceKind sourceKind,
            VoxelizedSection.LightSourceKind lightSourceKind,
            VoxelizedSection.Confidence confidence
    ) {
        VoxelizedSection.SourceKind safeSource = sourceKind == null
                ? VoxelizedSection.SourceKind.UNKNOWN
                : sourceKind;
        VoxelizedSection.LightSourceKind safeLight = lightSourceKind == null
                ? VoxelizedSection.LightSourceKind.UNKNOWN
                : lightSourceKind;
        VoxelizedSection.Confidence safeConfidence = confidence == null
                ? VoxelizedSection.Confidence.UNKNOWN
                : confidence;
        switch (safeSource) {
            case REAL_CHUNK -> this.boundarySourceRealChunkSections.incrementAndGet();
            case SURFACE_PREVIEW -> {
                this.boundarySourceSurfacePreviewSections.incrementAndGet();
                this.boundaryDegradedPreviewSections.incrementAndGet();
            }
            case SYNTHETIC_PREVIEW -> {
                this.boundarySourceSyntheticPreviewSections.incrementAndGet();
                this.boundaryDegradedPreviewSections.incrementAndGet();
            }
            case UNKNOWN, ZERO_CLEAR -> this.boundarySourceUnknownSections.incrementAndGet();
        }
        if (safeSource != VoxelizedSection.SourceKind.REAL_CHUNK
                || !safeConfidence.atLeast(VoxelizedSection.Confidence.MEDIUM)
                || !VoxelizedSection.isTrustedLight(safeLight)) {
            if (safeSource == VoxelizedSection.SourceKind.REAL_CHUNK) {
                this.boundarySourceUntrustedRealSections.incrementAndGet();
            }
            this.invalidRejectedCells.incrementAndGet();
        }
        this.refreshBoundaryDiagnostics();
    }

    public void recordUnsupportedPass(VoxyTerrainPass pass) {
        VoxyTerrainFailureReason reason = VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED;
        this.unsupportedPassSkips.incrementAndGet();
        this.refreshBoundaryDiagnostics();
    }

    public void recordIrisFailClosed(String reason) {
        this.irisFailClosedSkips.incrementAndGet();
        this.refreshBoundaryDiagnostics();
    }

    public void recordQueueDepths(long boundaryQueueDepth, long farQueueDepth) {
        this.diagnostics = this.diagnostics.withQueues(boundaryQueueDepth, farQueueDepth);
    }

    public VoxyTerrainOwnershipCell classifyAndRecordChunk(
            int chunkX,
            int chunkZ,
            int chunkRadius,
            boolean exactLodAvailable,
            boolean parentFallbackAvailable,
            boolean tileRejected
    ) {
        VoxyTerrainOwnershipMap ownershipMap = new VoxyTerrainOwnershipMap(this.snapshot());
        VoxyTerrainOwnershipCell cell = ownershipMap.classifyChunkRadius(
                chunkX,
                chunkZ,
                chunkRadius,
                exactLodAvailable,
                parentFallbackAvailable,
                tileRejected
        );
        this.recordOwnershipDecision(cell);
        if (exactLodAvailable && parentFallbackAvailable && cell.ownership() == VoxyTerrainOwnership.VOXY_EXACT_LOD) {
            this.parentChildConflictCells.incrementAndGet();
            this.refreshBoundaryDiagnostics();
        }
        return cell;
    }

    public void recordOwnershipDecision(VoxyTerrainOwnershipCell cell) {
        this.recordOwnershipDecision(this.nextSyntheticBoundaryKey(), cell);
    }

    public void recordOwnershipDecision(long sectionKey, VoxyTerrainOwnershipCell cell) {
        switch (cell.ownership()) {
            case VANILLA_EXACT -> this.exactOwnedCells.incrementAndGet();
            case VOXY_EXACT_LOD -> {
                this.lodOwnedCells.incrementAndGet();
                this.boundaryExactSections.incrementAndGet();
                this.recordBoundaryCoverage(sectionKey, BoundaryCoverageState.EXACT);
            }
            case VOXY_PARENT_FALLBACK -> {
                this.parentFallbackCells.incrementAndGet();
                this.boundaryParentFallbackSections.incrementAndGet();
                this.recordBoundaryCoverage(sectionKey, BoundaryCoverageState.FALLBACK);
            }
            case REJECTED_INVALID -> {
                this.invalidRejectedCells.incrementAndGet();
                if (cell.reason() == VoxyTerrainFailureReason.MISSING_EXACT_CHILD) {
                    this.boundaryMissingSections.incrementAndGet();
                    this.recordBoundaryCoverage(sectionKey, BoundaryCoverageState.MISSING);
                } else {
                    this.boundaryRejectedSections.incrementAndGet();
                    this.recordBoundaryCoverage(sectionKey, BoundaryCoverageState.REJECTED);
                }
            }
            case EMPTY_OUTSIDE_DISTANCE -> {
            }
        }
        this.refreshBoundaryDiagnostics();
    }

    public VoxyProviderMeshApproval approveMesh(long sectionKey, VoxyTerrainOwnershipCell cell, int meshId, long requestEpoch) {
        return this.approveMesh(
                sectionKey,
                cell,
                meshId,
                requestEpoch,
                VoxyProviderRenderCell.PASS_SOLID
        );
    }

    public VoxyProviderMeshApproval approveMesh(
            long sectionKey,
            VoxyTerrainOwnershipCell cell,
            int meshId,
            long requestEpoch,
            int passMask
    ) {
        if (cell == null) {
            return VoxyProviderMeshApproval.rejected(VoxyTerrainFailureReason.UNTRUSTED_SOURCE, VoxyTerrainOwnership.REJECTED_INVALID);
        }
        if (!cell.rendersVoxyGeometry()) {
            return VoxyProviderMeshApproval.rejected(cell.reason(), cell.ownership());
        }
        if (meshId < 0) {
            return VoxyProviderMeshApproval.rejected(VoxyTerrainFailureReason.SECTION_OUT_OF_BOUNDS, cell.ownership());
        }
        long invalidationVersion = this.invalidationTracker.version(sectionKey);
        if (invalidationVersion > 0 && requestEpoch < invalidationVersion) {
            return VoxyProviderMeshApproval.rejected(VoxyTerrainFailureReason.STALE_UPLOAD, cell.ownership());
        }
        if (passMask == 0 || Integer.bitCount(passMask) != 1) {
            return VoxyProviderMeshApproval.rejected(VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED, cell.ownership());
        }
        return VoxyProviderMeshApproval.approved(
                cell.ownership(),
                passMask
        );
    }

    public void recordCommittedMesh(long sectionKey, VoxyTerrainOwnershipCell cell, int meshId, long requestEpoch) {
        this.recordCommittedMesh(sectionKey, cell, meshId, requestEpoch, VoxyProviderRenderCell.PASS_SOLID);
    }

    public void recordCommittedMesh(long sectionKey, VoxyTerrainOwnershipCell cell, int meshId, long requestEpoch, int passMask) {
        VoxyProviderMeshApproval approval = this.approveMesh(sectionKey, cell, meshId, requestEpoch, passMask);
        if (!approval.approved()) {
            this.renderIndex.remove(sectionKey);
            this.recordRejectedMeshCommit(sectionKey, approval);
            this.refreshBoundaryDiagnostics();
            return;
        }
        this.recordOwnershipDecision(sectionKey, cell);
        VoxyProviderRenderIndex.RecordResult recordResult = this.renderIndex.record(new VoxyProviderRenderCell(
                sectionKey,
                meshId,
                cell.ownership(),
                approval.reason(),
                requestEpoch,
                approval.passMask(),
                true,
                true,
                false
        ));
        this.applyRenderIndexRecordResult(sectionKey, recordResult);
        this.refreshBoundaryDiagnostics();
    }

    public void beginCurrentOwnershipRefresh() {
        this.boundaryCoverage.clear();
        this.currentRefreshRenderSections = ConcurrentHashMap.newKeySet();
    }

    public void recordCurrentOwnershipDecision(long sectionKey, VoxyTerrainOwnershipCell cell) {
        switch (cell.ownership()) {
            case VOXY_EXACT_LOD -> this.recordBoundaryCoverageNoRefresh(sectionKey, BoundaryCoverageState.EXACT);
            case VOXY_PARENT_FALLBACK -> this.recordBoundaryCoverageNoRefresh(sectionKey, BoundaryCoverageState.FALLBACK);
            case REJECTED_INVALID -> {
                if (cell.reason() != VoxyTerrainFailureReason.MISSING_EXACT_CHILD) {
                    this.recordBoundaryCoverageNoRefresh(sectionKey, BoundaryCoverageState.REJECTED);
                }
            }
            case VANILLA_EXACT, EMPTY_OUTSIDE_DISTANCE -> {
            }
        }
    }

    public void recordCurrentRenderCell(long sectionKey, VoxyTerrainOwnershipCell cell, int meshId, long requestEpoch) {
        if (cell.rendersVoxyGeometry() && meshId >= 0) {
            int passMask = this.renderIndex.passMaskForSection(sectionKey, 0);
            if (passMask == 0 && cell.ownership() == VoxyTerrainOwnership.VOXY_PARENT_FALLBACK) {
                VoxyProviderRenderIndex.RecordResult suppressionResult = this.renderIndex.suppressIncomingParent(sectionKey);
                if (suppressionResult.suppressedIncomingParent()) {
                    this.applyRenderIndexRecordResult(sectionKey, suppressionResult);
                    this.retainCurrentRefreshDescendants(suppressionResult);
                    this.recordRetainedDescendantCoverage(suppressionResult);
                    return;
                }
            }
            VoxyProviderMeshApproval approval = this.approveMesh(sectionKey, cell, meshId, requestEpoch, passMask);
            if (!approval.approved()) {
                this.renderIndex.remove(sectionKey);
                this.recordRejectedMeshCommit(sectionKey, approval);
                return;
            }
            this.recordCurrentOwnershipDecision(sectionKey, cell);
            VoxyProviderRenderIndex.RecordResult recordResult = this.renderIndex.record(new VoxyProviderRenderCell(
                    sectionKey,
                    meshId,
                    cell.ownership(),
                    approval.reason(),
                    requestEpoch,
                    approval.passMask(),
                    true,
                    true,
                    false
            ));
            this.applyRenderIndexRecordResult(sectionKey, recordResult);
            if (recordResult.recorded()) {
                Set<Long> refreshedSections = this.currentRefreshRenderSections;
                if (refreshedSections != null) {
                    refreshedSections.add(sectionKey);
                }
            } else if (recordResult.suppressedIncomingParent()) {
                this.retainCurrentRefreshDescendants(recordResult);
                this.recordRetainedDescendantCoverage(recordResult);
            }
        } else {
            this.recordCurrentOwnershipDecision(sectionKey, cell);
            this.renderIndex.remove(sectionKey);
        }
    }

    public void finishCurrentOwnershipRefresh() {
        Set<Long> refreshedSections = this.currentRefreshRenderSections;
        this.currentRefreshRenderSections = null;
        if (refreshedSections != null) {
            this.renderIndex.retainOnly(refreshedSections);
        }
        this.refreshBoundaryDiagnostics();
    }

    public boolean shouldRenderDuringSodiumPass(VoxyTerrainPass pass) {
        boolean supportedPass = this.supportsIndependentDrawPass(pass);
        if (!supportedPass) {
            this.recordUnsupportedPass(pass);
            return false;
        }
        return this.isSodiumProviderPassRenderable(pass);
    }

    public boolean shouldDrawProviderGeometry(VoxyTerrainPass pass) {
        return this.drawDecision(pass).draw();
    }

    public VoxyProviderDrawDecision drawDecision(VoxyTerrainPass pass) {
        VoxyTerrainPass safePass = pass == null ? VoxyTerrainPass.DEBUG : pass;
        boolean supportedPass = this.supportsIndependentDrawPass(safePass);
        boolean sodiumCompatible = supportedPass && this.isSodiumProviderPassRenderable(safePass);
        String authorityVerdict = this.providerRenderAuthorityVerdict();
        VoxyProviderRenderList renderList = supportedPass
                ? this.renderIndex.snapshotForPass(safePass).withAuthorityVerdict(authorityVerdict)
                : VoxyProviderRenderList.empty(safePass, 0, authorityVerdict);
        this.recordRenderListSnapshot(renderList);
        long sectionCount = renderList.sectionCount();
        if (!supportedPass) {
            this.recordUnsupportedPass(safePass);
            return VoxyProviderDrawDecision.skip(
                    safePass,
                    sectionCount,
                    this.providerRenderAuthorityVerdict(),
                    VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED
            );
        }
        if (!sodiumCompatible) {
            return VoxyProviderDrawDecision.skip(
                    safePass,
                    renderList,
                    authorityVerdict,
                    VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED
            );
        }
        if (!PASS_PROVIDER_RENDER_AUTHORITY.equals(authorityVerdict)) {
            return VoxyProviderDrawDecision.skip(
                    safePass,
                    renderList,
                    authorityVerdict,
                    this.reasonForAuthorityVerdict(authorityVerdict)
            );
        }
        if (sectionCount <= 0) {
            return VoxyProviderDrawDecision.skip(
                    safePass,
                    renderList,
                    authorityVerdict,
                    VoxyTerrainFailureReason.MISSING_EXACT_CHILD
            );
        }
        return VoxyProviderDrawDecision.draw(
                safePass,
                renderList
        );
    }

    public void recordRenderedPass(VoxyTerrainPass pass) {
        this.diagnostics = this.diagnostics.withPassMesh(pass);
        this.refreshBoundaryDiagnostics();
    }

    public void recordProviderRenderListDispatch(VoxyProviderRenderList renderList) {
        if (renderList == null) {
            return;
        }
        this.recordRenderListSnapshot(renderList);
        this.providerCommandGenerationCount.incrementAndGet();
        this.providerTraversalBypassCount.incrementAndGet();
    }

    public void recordProviderRenderListStaleSkip() {
        this.providerRenderListStaleSkips.incrementAndGet();
    }

    public String providerRenderAuthorityVerdict() {
        return this.diagnostics.providerRenderAuthorityVerdict();
    }

    public long renderIndexSize() {
        return this.renderIndex.size();
    }

    public long renderOwnedSections() {
        return this.renderIndex.renderOwnedSections();
    }

    public long providerDrawnSolidSections() {
        return this.supportsIndependentDrawPass(VoxyTerrainPass.SOLID)
                ? this.renderIndex.sectionsForPass(VoxyTerrainPass.SOLID)
                : 0;
    }

    public long providerDrawnCutoutSections() {
        return this.supportsIndependentDrawPass(VoxyTerrainPass.CUTOUT)
                ? this.renderIndex.sectionsForPass(VoxyTerrainPass.CUTOUT)
                : 0;
    }

    public long staleUploadRejections() {
        return this.staleUploadRejections.get();
    }

    public long parentSuppressedSections() {
        return this.parentSuppressedSections.get();
    }

    public long providerRenderListLength() {
        return this.providerRenderListLength.get();
    }

    public long providerRenderListEpoch() {
        return this.providerRenderListEpoch.get();
    }

    public long providerRenderListStaleSkips() {
        return this.providerRenderListStaleSkips.get();
    }

    public long providerCommandGenerationCount() {
        return this.providerCommandGenerationCount.get();
    }

    public long providerTraversalBypassCount() {
        return this.providerTraversalBypassCount.get();
    }

    private void refreshBoundaryDiagnostics() {
        long currentBoundaryExactSections = 0;
        long currentBoundaryParentFallbackSections = 0;
        long currentBoundaryRejectedSections = 0;
        long currentBoundaryMissingSections = 0;
        for (BoundaryCoverageState state : this.boundaryCoverage.values()) {
            switch (state) {
                case EXACT -> currentBoundaryExactSections++;
                case FALLBACK -> currentBoundaryParentFallbackSections++;
                case REJECTED -> currentBoundaryRejectedSections++;
                case MISSING -> currentBoundaryMissingSections++;
            }
        }
        String visualSourceVerdict = this.computeVisualSourceVerdict(
                currentBoundaryExactSections + currentBoundaryParentFallbackSections > 0);
        this.diagnostics = this.diagnostics.withOwnership(
                this.exactOwnedCells.get(),
                this.lodOwnedCells.get(),
                this.parentFallbackCells.get(),
                this.invalidRejectedCells.get(),
                this.parentChildConflictCells.get(),
                currentBoundaryExactSections,
                currentBoundaryParentFallbackSections,
                currentBoundaryRejectedSections,
                currentBoundaryMissingSections,
                this.invalidRenderedSections.get(),
                this.boundarySourceRealChunkSections.get(),
                this.boundarySourceSurfacePreviewSections.get(),
                this.boundarySourceSyntheticPreviewSections.get(),
                this.boundarySourceUnknownSections.get(),
                this.boundarySourceUntrustedRealSections.get(),
                this.boundaryDegradedPreviewSections.get(),
                this.unsupportedPassSkips.get(),
                this.irisFailClosedSkips.get(),
                this.computeTerrainCoverageVerdict(
                        currentBoundaryExactSections,
                        currentBoundaryParentFallbackSections,
                        currentBoundaryRejectedSections,
                        currentBoundaryMissingSections,
                        visualSourceVerdict
                ),
                visualSourceVerdict,
                this.computeProviderRenderAuthorityVerdict(
                        currentBoundaryExactSections,
                        currentBoundaryParentFallbackSections,
                        currentBoundaryRejectedSections,
                        currentBoundaryMissingSections
                )
        );
    }

    private String computeProviderRenderAuthorityVerdict(
            long currentBoundaryExactSections,
            long currentBoundaryParentFallbackSections,
            long currentBoundaryRejectedSections,
            long currentBoundaryMissingSections
    ) {
        if (this.diagnostics.unsupportedPassRenderedSections() > 0) {
            return FAIL_UNSUPPORTED_PASS_RENDERED;
        }
        if (this.invalidRenderedSections.get() > 0) {
            return FAIL_INVALID_RENDERED;
        }
        if (this.staleUploadRejections.get() > 0 && this.productionSupportedRenderOwnedSections() == 0) {
            return FAIL_STALE_UPLOAD_RENDERED;
        }
        if (this.parentChildConflictCells.get() > 0) {
            return FAIL_OWNERSHIP_CONFLICT;
        }
        if (currentBoundaryMissingSections > 0) {
            return FAIL_GAP;
        }
        if (currentBoundaryRejectedSections > 0) {
            return FAIL_UNTRUSTED_SOURCE;
        }
        if (!this.snapshot().sodiumChunkRenderingEnabled()
                || !this.snapshot().hasMergedDistanceOwnership()) {
            return UNKNOWN_NO_BOUNDARY_REQUESTS;
        }
        long currentBoundaryCoverage = currentBoundaryExactSections + currentBoundaryParentFallbackSections;
        if (currentBoundaryCoverage > 0 && this.productionSupportedRenderOwnedSections() > 0) {
            return PASS_PROVIDER_RENDER_AUTHORITY;
        }
        if (currentBoundaryCoverage > 0 || this.renderIndex.renderOwnedSections() > 0) {
            return FAIL_GAP;
        }
        return UNKNOWN_NO_BOUNDARY_REQUESTS;
    }

    private String computeTerrainCoverageVerdict(
            long currentBoundaryExactSections,
            long currentBoundaryParentFallbackSections,
            long currentBoundaryRejectedSections,
            long currentBoundaryMissingSections,
            String visualSourceVerdict
    ) {
        if (this.parentChildConflictCells.get() > 0) {
            return FAIL_OWNERSHIP_CONFLICT;
        }
        if (this.diagnostics.unsupportedPassRenderedSections() > 0) {
            return FAIL_UNSUPPORTED_PASS_RENDERED;
        }
        if (this.invalidRenderedSections.get() > 0) {
            return FAIL_INVALID_RENDERED;
        }
        if (this.staleUploadRejections.get() > 0 && this.productionSupportedRenderOwnedSections() == 0) {
            return FAIL_STALE_UPLOAD_RENDERED;
        }
        if (currentBoundaryMissingSections > 0) {
            return FAIL_GAP;
        }
        if (currentBoundaryRejectedSections > 0) {
            return FAIL_UNTRUSTED_SOURCE;
        }
        if (FAIL_UNTRUSTED_SOURCE.equals(visualSourceVerdict)) {
            return FAIL_UNTRUSTED_SOURCE;
        }
        long currentBoundaryCoverage = currentBoundaryExactSections + currentBoundaryParentFallbackSections;
        if (currentBoundaryCoverage > 0 && this.productionSupportedRenderOwnedSections() > 0) {
            return PASS_NO_GAP;
        }
        if (currentBoundaryCoverage > 0 || this.renderIndex.renderOwnedSections() > 0) {
            return FAIL_GAP;
        }
        return UNKNOWN_NO_BOUNDARY_REQUESTS;
    }

    private String computeVisualSourceVerdict(boolean hasCurrentBoundaryCoverage) {
        long sourceCount = this.boundarySourceRealChunkSections.get()
                + this.boundarySourceSurfacePreviewSections.get()
                + this.boundarySourceSyntheticPreviewSections.get()
                + this.boundarySourceUnknownSections.get();
        if (this.boundarySourceSurfacePreviewSections.get() > 0
                || this.boundarySourceSyntheticPreviewSections.get() > 0
                || this.boundarySourceUnknownSections.get() > 0
                || this.boundarySourceUntrustedRealSections.get() > 0
                || this.boundaryDegradedPreviewSections.get() > 0) {
            return FAIL_UNTRUSTED_SOURCE;
        }
        if (sourceCount == 0 && !hasCurrentBoundaryCoverage) {
            return UNKNOWN_NO_BOUNDARY_REQUESTS;
        }
        return PASS_VISUAL_SOURCE_CORRECTNESS;
    }

    private long boundarySourceSections() {
        return this.boundarySourceRealChunkSections.get()
                + this.boundarySourceSurfacePreviewSections.get()
                + this.boundarySourceSyntheticPreviewSections.get()
                + this.boundarySourceUnknownSections.get();
    }

    private void recordRejectedMeshCommit(long sectionKey, VoxyProviderMeshApproval approval) {
        VoxyTerrainFailureReason reason = approval.reason();
        if (reason == VoxyTerrainFailureReason.MISSING_EXACT_CHILD) {
            this.boundaryMissingSections.incrementAndGet();
            this.recordBoundaryCoverageNoRefresh(sectionKey, BoundaryCoverageState.MISSING);
            return;
        }
        if (reason == VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED) {
            this.unsupportedPassSkips.incrementAndGet();
        }
        if (reason == VoxyTerrainFailureReason.STALE_UPLOAD) {
            this.staleUploadRejections.incrementAndGet();
            return;
        }
        this.invalidRejectedCells.incrementAndGet();
        if (approval.ownership() == VoxyTerrainOwnership.REJECTED_INVALID) {
            return;
        }
        this.boundaryRejectedSections.incrementAndGet();
        this.recordBoundaryCoverageNoRefresh(sectionKey, BoundaryCoverageState.REJECTED);
    }

    private long productionSupportedRenderOwnedSections() {
        return this.supportsIndependentDrawPass(VoxyTerrainPass.SOLID)
                ? this.renderIndex.sectionsForPass(VoxyTerrainPass.SOLID)
                : 0;
    }

    private boolean supportsIndependentDrawPass(VoxyTerrainPass pass) {
        return pass == VoxyTerrainPass.SOLID;
    }

    private boolean isSodiumProviderPassRenderable(VoxyTerrainPass pass) {
        return this.supportsIndependentDrawPass(pass)
                && this.snapshot().sodiumChunkRenderingEnabled()
                && this.snapshot().hasMergedDistanceOwnership();
    }

    private void applyRenderIndexRecordResult(long incomingSectionKey, VoxyProviderRenderIndex.RecordResult recordResult) {
        if (recordResult.suppressedIncomingParent()) {
            this.parentSuppressedSections.incrementAndGet();
            this.boundaryCoverage.remove(incomingSectionKey);
        }
        long[] suppressedAncestorSectionKeys = recordResult.suppressedAncestorSectionKeys();
        if (suppressedAncestorSectionKeys.length > 0) {
            this.parentSuppressedSections.addAndGet(suppressedAncestorSectionKeys.length);
            for (long suppressedAncestorSectionKey : suppressedAncestorSectionKeys) {
                this.boundaryCoverage.remove(suppressedAncestorSectionKey);
            }
        }
    }

    private void retainCurrentRefreshDescendants(VoxyProviderRenderIndex.RecordResult recordResult) {
        Set<Long> refreshedSections = this.currentRefreshRenderSections;
        if (refreshedSections != null) {
            for (long retainedDescendantSectionKey : recordResult.retainedDescendantSectionKeys()) {
                refreshedSections.add(retainedDescendantSectionKey);
            }
        }
    }

    private void recordRetainedDescendantCoverage(VoxyProviderRenderIndex.RecordResult recordResult) {
        for (long retainedDescendantSectionKey : recordResult.retainedDescendantSectionKeys()) {
            VoxyTerrainOwnership ownership = this.renderIndex.ownershipForSection(retainedDescendantSectionKey);
            if (ownership == VoxyTerrainOwnership.VOXY_EXACT_LOD) {
                this.recordBoundaryCoverageNoRefresh(retainedDescendantSectionKey, BoundaryCoverageState.EXACT);
            } else if (ownership == VoxyTerrainOwnership.VOXY_PARENT_FALLBACK) {
                this.recordBoundaryCoverageNoRefresh(retainedDescendantSectionKey, BoundaryCoverageState.FALLBACK);
            }
        }
    }

    private void recordRenderListSnapshot(VoxyProviderRenderList renderList) {
        this.providerRenderListLength.set(renderList.sectionCount());
        this.providerRenderListEpoch.set(renderList.epoch());
    }

    private VoxyTerrainFailureReason reasonForAuthorityVerdict(String authorityVerdict) {
        if (FAIL_INVALID_RENDERED.equals(authorityVerdict)) {
            return VoxyTerrainFailureReason.UNRESOLVED_BLOCK_STATE;
        }
        if (FAIL_OWNERSHIP_CONFLICT.equals(authorityVerdict)) {
            return VoxyTerrainFailureReason.PARENT_CHILD_OWNERSHIP_CONFLICT;
        }
        if (FAIL_UNTRUSTED_SOURCE.equals(authorityVerdict)) {
            return VoxyTerrainFailureReason.UNTRUSTED_SOURCE;
        }
        if (FAIL_UNSUPPORTED_PASS_RENDERED.equals(authorityVerdict)) {
            return VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED;
        }
        if (FAIL_STALE_UPLOAD_RENDERED.equals(authorityVerdict)) {
            return VoxyTerrainFailureReason.STALE_UPLOAD;
        }
        return VoxyTerrainFailureReason.MISSING_EXACT_CHILD;
    }

    private long nextSyntheticBoundaryKey() {
        return this.syntheticBoundaryKey.getAndIncrement();
    }

    private void recordBoundaryCoverage(long sectionKey, BoundaryCoverageState state) {
        this.recordBoundaryCoverageNoRefresh(sectionKey, state);
        this.refreshBoundaryDiagnostics();
    }

    private void recordBoundaryCoverageNoRefresh(long sectionKey, BoundaryCoverageState state) {
        this.boundaryCoverage.merge(sectionKey, state, VoxyFarTerrainProvider::mergeBoundaryCoverage);
    }

    private static BoundaryCoverageState mergeBoundaryCoverage(BoundaryCoverageState previous, BoundaryCoverageState incoming) {
        if (incoming == BoundaryCoverageState.EXACT) {
            return BoundaryCoverageState.EXACT;
        }
        if (incoming == BoundaryCoverageState.FALLBACK) {
            return previous == BoundaryCoverageState.EXACT
                    ? BoundaryCoverageState.EXACT
                    : BoundaryCoverageState.FALLBACK;
        }
        if (incoming == BoundaryCoverageState.REJECTED || incoming == BoundaryCoverageState.MISSING) {
            return incoming;
        }
        if (previous == BoundaryCoverageState.EXACT) {
            return BoundaryCoverageState.EXACT;
        }
        if (previous == BoundaryCoverageState.FALLBACK) {
            return BoundaryCoverageState.FALLBACK;
        }
        return BoundaryCoverageState.MISSING;
    }
}
