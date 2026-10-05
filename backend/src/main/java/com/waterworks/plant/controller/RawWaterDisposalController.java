package com.waterworks.plant.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waterworks.plant.common.ApiResponse;
import com.waterworks.plant.dto.AddEquipmentRequest;
import com.waterworks.plant.dto.CreateDisposalRequest;
import com.waterworks.plant.dto.DisposalDetail;
import com.waterworks.plant.dto.DisposalSummary;
import com.waterworks.plant.dto.DosingResult;
import com.waterworks.plant.dto.OperatorRequest;
import com.waterworks.plant.dto.RegisterDosingRequest;
import com.waterworks.plant.dto.ReleaseDisposalRequest;
import com.waterworks.plant.dto.ResolveConflictRequest;
import com.waterworks.plant.dto.StartDisposalRequest;
import com.waterworks.plant.service.DisposalService;

import jakarta.validation.Valid;

/** 原水超标联动处置闭环接口。 */
@RestController
@RequestMapping("/api/raw-water-disposals")
public class RawWaterDisposalController {

    private final DisposalService service;

    public RawWaterDisposalController(DisposalService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<DisposalSummary>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<DisposalDetail> get(@PathVariable Long id) {
        return ApiResponse.success(service.getDetail(id));
    }

    @PostMapping
    public ApiResponse<DisposalDetail> create(@Valid @RequestBody CreateDisposalRequest request) {
        return ApiResponse.success("联动处置已发起", service.create(request));
    }

    @PostMapping("/{id}/equipment")
    public ApiResponse<DisposalDetail> addEquipment(@PathVariable Long id,
                                                    @Valid @RequestBody AddEquipmentRequest request) {
        return ApiResponse.success("设备已关联", service.addEquipment(id, request));
    }

    @PostMapping("/{id}/equipment/{linkId}/confirm")
    public ApiResponse<DisposalDetail> confirmEquipment(@PathVariable Long id,
                                                        @PathVariable Long linkId,
                                                        @Valid @RequestBody OperatorRequest request) {
        return ApiResponse.success("设备已确认", service.confirmEquipment(id, linkId, request));
    }

    @PostMapping("/{id}/equipment/{linkId}/resolve-conflict")
    public ApiResponse<DisposalDetail> resolveConflict(@PathVariable Long id,
                                                       @PathVariable Long linkId,
                                                       @Valid @RequestBody ResolveConflictRequest request) {
        return ApiResponse.success("设备冲突已处理", service.resolveConflict(id, linkId, request));
    }

    @PostMapping("/{id}/equipment/{linkId}/remove")
    public ApiResponse<DisposalDetail> removePending(@PathVariable Long id,
                                                     @PathVariable Long linkId,
                                                     @RequestBody Map<String, String> request) {
        return ApiResponse.success("待确认设备已移除",
                service.removePendingLink(id, linkId, request.get("operator")));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<DisposalDetail> start(@PathVariable Long id,
                                             @Valid @RequestBody StartDisposalRequest request) {
        return ApiResponse.success("已进入执行中", service.start(id, request));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<DisposalDetail> release(@PathVariable Long id,
                                               @Valid @RequestBody ReleaseDisposalRequest request) {
        return ApiResponse.success("处置已解除", service.release(id, request));
    }

    @PostMapping("/{id}/refresh-conflicts")
    public ApiResponse<DisposalDetail> refresh(@PathVariable Long id,
                                               @RequestBody Map<String, String> request) {
        return ApiResponse.success("已重新核对设备冲突",
                service.refreshConflicts(id, request.get("operator")));
    }

    @PostMapping("/{id}/dosings")
    public ApiResponse<DosingResult> registerDosing(@PathVariable Long id,
                                                    @Valid @RequestBody RegisterDosingRequest request) {
        DosingResult result = service.registerDosing(id, request);
        return ApiResponse.success(result.message(), result);
    }
}
