package com.waterworks.plant.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.ConflictException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.dto.AddEquipmentRequest;
import com.waterworks.plant.dto.CreateDisposalRequest;
import com.waterworks.plant.dto.DisposalDetail;
import com.waterworks.plant.dto.DisposalEquipmentView;
import com.waterworks.plant.dto.DisposalFlowLogView;
import com.waterworks.plant.dto.DisposalSummary;
import com.waterworks.plant.dto.DosingResult;
import com.waterworks.plant.dto.RawWaterView;
import com.waterworks.plant.dto.RegisterDosingRequest;
import com.waterworks.plant.dto.ReleaseDisposalRequest;
import com.waterworks.plant.dto.ResolveConflictRequest;
import com.waterworks.plant.dto.StartDisposalRequest;
import com.waterworks.plant.entity.DisposalEquipment;
import com.waterworks.plant.entity.DisposalFlowLog;
import com.waterworks.plant.entity.Dosing;
import com.waterworks.plant.entity.Filter;
import com.waterworks.plant.entity.RawWater;
import com.waterworks.plant.entity.RawWaterDisposal;
import com.waterworks.plant.entity.SedimentationTank;
import com.waterworks.plant.repository.DisposalEquipmentRepository;
import com.waterworks.plant.repository.DisposalFlowLogRepository;
import com.waterworks.plant.repository.DosingRepository;
import com.waterworks.plant.repository.FilterRepository;
import com.waterworks.plant.repository.RawWaterDisposalRepository;
import com.waterworks.plant.repository.RawWaterRepository;
import com.waterworks.plant.repository.SedimentationTankRepository;

/**
 * 原水超标联动处置闭环。
 *
 * <p>并发唯一性依赖 {@code uk_active_source_name} 数据库约束，不只依赖页面或先查后插；
 * 状态推进、设备冲突、加药留痕均在后端事务内完成。涉及设备与处置单的修改顺序统一为
 * “先锁设备，再锁处置单”，避免设备状态流转与处置操作之间形成死锁。</p>
 */
@Service
public class DisposalService {

    private final RawWaterRepository rawWaterRepository;
    private final RawWaterDisposalRepository disposalRepository;
    private final DisposalEquipmentRepository equipmentRepository;
    private final DisposalFlowLogRepository flowLogRepository;
    private final SedimentationTankRepository tankRepository;
    private final FilterRepository filterRepository;
    private final DosingRepository dosingRepository;
    private final DisposalService self;

    public DisposalService(RawWaterRepository rawWaterRepository,
                           RawWaterDisposalRepository disposalRepository,
                           DisposalEquipmentRepository equipmentRepository,
                           DisposalFlowLogRepository flowLogRepository,
                           SedimentationTankRepository tankRepository,
                           FilterRepository filterRepository,
                           DosingRepository dosingRepository,
                           @Lazy DisposalService self) {
        this.rawWaterRepository = rawWaterRepository;
        this.disposalRepository = disposalRepository;
        this.equipmentRepository = equipmentRepository;
        this.flowLogRepository = flowLogRepository;
        this.tankRepository = tankRepository;
        this.filterRepository = filterRepository;
        this.dosingRepository = dosingRepository;
        this.self = self;
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<DisposalSummary> list(String status) {
        List<RawWaterDisposal> rows = status == null || status.isBlank()
                ? disposalRepository.findAllByOrderByCreatedAtDesc()
                : disposalRepository.findByStatusOrderByCreatedAtDesc(status.trim());
        return rows.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public DisposalDetail getDetail(Long id) {
        RawWaterDisposal disposal = getActiveOrReleased(id);
        RawWater rawWater = rawWaterRepository.findById(disposal.getRawWaterId())
                .orElseThrow(() -> new BizException("原水监测记录不存在：" + disposal.getRawWaterId()));
        List<DisposalEquipment> links = equipmentRepository
                .findByDisposalIdOrderByEquipmentTypeAscIdAsc(id);
        List<Dosing> dosings = dosingRepository.findByDisposalIdOrderByDosedAtDescIdDesc(id);
        List<DisposalFlowLog> logs = flowLogRepository.findByDisposalIdOrderByIdAsc(id);

        return new DisposalDetail(
                disposal.getId(),
                disposal.getRawWaterId(),
                disposal.getSourceName(),
                disposal.getStatus(),
                disposal.getAssignee(),
                disposal.getCreatedAt(),
                disposal.getStartedAt(),
                disposal.getReleasedAt(),
                disposal.getConclusion(),
                disposal.getReleaseOperator(),
                countByType(links, Dict.EQUIPMENT_TANK, null),
                countByType(links, Dict.EQUIPMENT_FILTER, null),
                countByType(links, Dict.EQUIPMENT_TANK, Dict.LINK_CONFIRMED),
                countByType(links, Dict.EQUIPMENT_FILTER, Dict.LINK_CONFIRMED),
                (int) links.stream().filter(l -> Dict.LINK_CONFLICT.equals(l.getStatus())).count(),
                dosings.size(),
                new RawWaterView(rawWater.getId(), rawWater.getSourceName(), rawWater.getSampleAt(),
                        rawWater.getWaterSample(), rawWater.getStatus()),
                links.stream().map(l -> toEquipmentView(l, currentEquipmentStatus(l))).toList(),
                dosings,
                logs.stream().map(this::toFlowView).toList());
    }

    // ---------------- 发起 / 推进 / 解除 ----------------

    public DisposalDetail create(CreateDisposalRequest request) {
        String assignee = required(request.assignee(), "责任值班人员");
        List<Long> tankIds = normalizeIds(request.tankIds());
        List<Long> filterIds = normalizeIds(request.filterIds());

        RawWater rawWater = rawWaterRepository.findById(request.rawWaterId())
                .orElseThrow(() -> new BizException("原水监测记录不存在：" + request.rawWaterId()));
        if (!"超标".equals(rawWater.getStatus())) {
            throw new BizException("只有已判为「超标」的原水监测记录才能发起联动处置");
        }
        disposalRepository.findByActiveSourceName(rawWater.getSourceName()).ifPresent(d -> {
            throw takeover(d);
        });

        try {
            Long id = self.createTransactional(rawWater, assignee, tankIds, filterIds);
            return getDetail(id);
        } catch (DataIntegrityViolationException e) {
            RawWaterDisposal takeover = disposalRepository
                    .findByActiveSourceName(rawWater.getSourceName())
                    .orElse(null);
            if (takeover == null) {
                throw e;
            }
            throw takeover(takeover);
        }
    }

    @Transactional
    public Long createTransactional(RawWater lockedRawWater, String assignee,
                                    List<Long> tankIds, List<Long> filterIds) {
        // 与设备清洗 / 反洗保持“先锁设备，再写处置单”的顺序，避免并发时形成锁环。
        List<AbstractMap.SimpleEntry<String, Long>> selected = new ArrayList<>();
        tankIds.forEach(id -> selected.add(new AbstractMap.SimpleEntry<>(Dict.EQUIPMENT_TANK, id)));
        filterIds.forEach(id -> selected.add(new AbstractMap.SimpleEntry<>(Dict.EQUIPMENT_FILTER, id)));
        selected.sort(Comparator
                .comparing((AbstractMap.SimpleEntry<String, Long> e) -> e.getKey())
                .thenComparing(AbstractMap.SimpleEntry::getValue));

        List<AbstractMap.SimpleEntry<String, String>> selectedCodes = new ArrayList<>();
        for (AbstractMap.SimpleEntry<String, Long> item : selected) {
            String code;
            if (Dict.EQUIPMENT_TANK.equals(item.getKey())) {
                SedimentationTank tank = tankRepository.findLockedById(item.getValue())
                        .orElseThrow(() -> new BizException("沉淀池不存在：" + item.getValue()));
                if (!"运行".equals(tank.getStatus())) {
                    throw new BizException("发起时只能选择「运行」中的沉淀池：" + tank.getTankCode());
                }
                code = tank.getTankCode();
            } else {
                Filter filter = filterRepository.findLockedById(item.getValue())
                        .orElseThrow(() -> new BizException("滤池不存在：" + item.getValue()));
                if (!"运行".equals(filter.getStatus())) {
                    throw new BizException("发起时只能选择「运行」中的滤池：" + filter.getFilterCode());
                }
                code = filter.getFilterCode();
            }
            selectedCodes.add(new AbstractMap.SimpleEntry<>(item.getKey(), code));
        }

        LocalDateTime now = LocalDateTime.now();
        RawWaterDisposal disposal = new RawWaterDisposal();
        disposal.setRawWaterId(lockedRawWater.getId());
        disposal.setSourceName(lockedRawWater.getSourceName());
        disposal.setActiveSourceName(lockedRawWater.getSourceName());
        disposal.setStatus(Dict.DISPOSAL_PENDING);
        disposal.setAssignee(assignee);
        disposal.setCreatedAt(now);
        disposal.setUpdatedAt(now);
        disposalRepository.saveAndFlush(disposal);

        for (int i = 0; i < selected.size(); i++) {
            AbstractMap.SimpleEntry<String, Long> item = selected.get(i);
            saveNewLink(disposal.getId(), item.getKey(), item.getValue(),
                    selectedCodes.get(i).getValue(), Dict.LINK_CONFIRMED, assignee, now);
        }

        log(disposal.getId(), "发起处置", null, Dict.DISPOSAL_PENDING, assignee,
                "从原水监测记录 #" + lockedRawWater.getId() + " 发起联动处置", now);
        return disposal.getId();
    }

    @Transactional
    public DisposalDetail addEquipment(Long disposalId, AddEquipmentRequest request) {
        String operator = required(request.operator(), "操作值班人员");
        String type = request.equipmentType() == null ? "" : request.equipmentType().trim();
        if (!Dict.isValidDisposalEquipmentType(type)) {
            throw new BizException("设备类型必须为 沉淀池 / 滤池");
        }

        // 先锁设备，再锁处置单，和设备清洗 / 反洗事务使用同一锁顺序。
        String code;
        if (Dict.EQUIPMENT_TANK.equals(type)) {
            SedimentationTank tank = tankRepository.findLockedById(request.equipmentId())
                    .orElseThrow(() -> new BizException("沉淀池不存在：" + request.equipmentId()));
            if (!"运行".equals(tank.getStatus())) {
                throw new BizException("只能关联「运行」中的沉淀池，当前状态：" + tank.getStatus());
            }
            code = tank.getTankCode();
        } else {
            Filter filter = filterRepository.findLockedById(request.equipmentId())
                    .orElseThrow(() -> new BizException("滤池不存在：" + request.equipmentId()));
            if (!"运行".equals(filter.getStatus())) {
                throw new BizException("只能关联「运行」中的滤池，当前状态：" + filter.getStatus());
            }
            code = filter.getFilterCode();
        }

        RawWaterDisposal disposal = lockDisposal(disposalId);
        requireActive(disposal);
        Optional<DisposalEquipment> existing = equipmentRepository
                .findByDisposalIdAndEquipmentTypeAndEquipmentId(disposal.getId(), type, request.equipmentId());
        LocalDateTime now = LocalDateTime.now();
        if (existing.isPresent()) {
            DisposalEquipment link = existing.get();
            if (Dict.isActiveLinkStatus(link.getStatus())) {
                throw new BizException(code + " 已关联本处置，当前状态：" + link.getStatus());
            }
            link.setStatus(Dict.LINK_CONFIRMED);
            link.setConflictReason(null);
            link.setExcludedReason(null);
            link.setResolvedBy(null);
            link.setResolvedAt(null);
            link.setUpdatedAt(now);
            equipmentRepository.save(link);
        } else {
            saveNewLink(disposal.getId(), type, request.equipmentId(), code,
                    Dict.LINK_CONFIRMED, operator, now);
        }
        log(disposal.getId(), "关联设备", null, disposal.getStatus(), operator,
                type + " " + code + " 已关联并确认", now);
        touch(disposal, now);
        return getDetail(disposal.getId());
    }

    @Transactional
    public DisposalDetail confirmEquipment(Long disposalId, Long linkId, com.waterworks.plant.dto.OperatorRequest request) {
        String operator = required(request.operator(), "操作值班人员");
        RawWaterDisposal beforeLock = getActiveOrReleased(disposalId);
        requireActive(beforeLock);
        DisposalEquipment beforeLink = equipmentRepository.findById(linkId)
                .orElseThrow(() -> new BizException("关联设备不存在：" + linkId));
        if (!beforeLink.getDisposalId().equals(disposalId)) {
            throw new BizException("设备关联不属于本处置单：" + linkId);
        }
        String currentStatus = lockAndReadEquipmentStatus(beforeLink);

        RawWaterDisposal disposal = lockDisposal(disposalId);
        requireActive(disposal);
        DisposalEquipment link = equipmentRepository.findById(linkId)
                .orElseThrow(() -> new BizException("关联设备不存在：" + linkId));
        LocalDateTime now = LocalDateTime.now();
        if (!"运行".equals(currentStatus)) {
            throw new BizException(link.getEquipmentCode() + " 当前为「" + currentStatus
                    + "」，不能确认；请改选可用设备或填写排除原因");
        }
        if (!Dict.LINK_PENDING.equals(link.getStatus()) && !Dict.LINK_CONFIRMED.equals(link.getStatus())) {
            throw new BizException(link.getEquipmentCode() + " 当前为「" + link.getStatus()
                    + "」，不能确认");
        }
        if (Dict.LINK_PENDING.equals(link.getStatus())) {
            link.setStatus(Dict.LINK_CONFIRMED);
            link.setUpdatedAt(now);
            equipmentRepository.save(link);
        }
        log(disposalId, "确认设备", null, disposal.getStatus(), operator,
                "已确认关联工段 " + link.getEquipmentCode(), now);
        touch(disposal, now);
        return getDetail(disposalId);
    }

    @Transactional
    public DisposalDetail resolveConflict(Long disposalId, Long linkId, ResolveConflictRequest request) {
        String operator = required(request.operator(), "操作值班人员");
        boolean hasReplacement = request.replacementEquipmentId() != null;
        boolean hasExcludeReason = request.excludedReason() != null && !request.excludedReason().isBlank();
        if (hasReplacement == hasExcludeReason) {
            throw new BizException("冲突必须且只能通过“改选可用设备”或“填写排除原因”之一处理");
        }

        DisposalEquipment conflict = equipmentRepository.findById(linkId)
                .orElseThrow(() -> new BizException("关联设备不存在：" + linkId));
        if (!conflict.getDisposalId().equals(disposalId)) {
            throw new BizException("设备关联不属于本处置单：" + linkId);
        }
        if (!Dict.LINK_CONFLICT.equals(conflict.getStatus())) {
            throw new BizException("只有「冲突待处理」关联需要处理，当前状态：" + conflict.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        Long replacementId = null;
        String newCode = null;
        if (hasReplacement) {
            replacementId = request.replacementEquipmentId();
            if (replacementId.equals(conflict.getEquipmentId())) {
                throw new BizException("改选设备不能与冲突设备相同");
            }
            if (Dict.EQUIPMENT_TANK.equals(conflict.getEquipmentType())) {
                SedimentationTank tank = tankRepository.findLockedById(request.replacementEquipmentId())
                        .orElseThrow(() -> new BizException("沉淀池不存在：" + request.replacementEquipmentId()));
                if (!"运行".equals(tank.getStatus())) {
                    throw new BizException("改选沉淀池必须处于「运行」，当前状态：" + tank.getStatus());
                }
                newCode = tank.getTankCode();
            } else {
                Filter filter = filterRepository.findLockedById(request.replacementEquipmentId())
                        .orElseThrow(() -> new BizException("滤池不存在：" + request.replacementEquipmentId()));
                if (!"运行".equals(filter.getStatus())) {
                    throw new BizException("改选滤池必须处于「运行」，当前状态：" + filter.getStatus());
                }
                newCode = filter.getFilterCode();
            }
        }

        RawWaterDisposal disposal = disposalRepository.findLockedById(disposalId)
                .orElseThrow(() -> new BizException("联动处置不存在：" + disposalId));
        requireActive(disposal);

        if (hasReplacement) {
            DisposalEquipment replacement = equipmentRepository
                    .findByDisposalIdAndEquipmentTypeAndEquipmentId(
                            disposalId, conflict.getEquipmentType(), replacementId)
                    .orElse(null);
            if (replacement != null) {
                if (Dict.isActiveLinkStatus(replacement.getStatus())) {
                    throw new BizException(newCode + " 已关联本处置，不能重复改选");
                }
                replacement.setStatus(Dict.LINK_CONFIRMED);
                replacement.setConflictReason(null);
                replacement.setExcludedReason(null);
                replacement.setResolvedBy(null);
                replacement.setResolvedAt(null);
                replacement.setUpdatedAt(now);
                equipmentRepository.save(replacement);
            } else {
                saveNewLink(disposalId, conflict.getEquipmentType(), replacementId, newCode,
                        Dict.LINK_CONFIRMED, operator, now);
            }
            conflict.setStatus(Dict.LINK_REPLACED);
            conflict.setResolvedBy(operator);
            conflict.setResolvedAt(now);
            conflict.setUpdatedAt(now);
            equipmentRepository.save(conflict);
            log(disposalId, "改选设备", null, disposal.getStatus(), operator,
                    conflict.getEquipmentCode() + " 已改选为 " + newCode, now);
        } else {
            String reason = request.excludedReason().trim();
            if (reason.length() > 128) {
                throw new BizException("排除原因不能超过 128 字");
            }
            conflict.setStatus(Dict.LINK_EXCLUDED);
            conflict.setExcludedReason(reason);
            conflict.setResolvedBy(operator);
            conflict.setResolvedAt(now);
            conflict.setUpdatedAt(now);
            equipmentRepository.save(conflict);
            log(disposalId, "排除设备", null, disposal.getStatus(), operator,
                    conflict.getEquipmentCode() + " 已排除：" + reason, now);
        }
        touch(disposal, now);
        return getDetail(disposalId);
    }

    @Transactional
    public DisposalDetail removePendingLink(Long disposalId, Long linkId, String operator) {
        String op = required(operator, "操作值班人员");
        RawWaterDisposal disposal = lockDisposal(disposalId);
        requireActive(disposal);
        DisposalEquipment link = equipmentRepository.findById(linkId)
                .orElseThrow(() -> new BizException("关联设备不存在：" + linkId));
        if (!link.getDisposalId().equals(disposalId) || !Dict.LINK_PENDING.equals(link.getStatus())) {
            throw new BizException("只能移除本处置单下的待确认关联");
        }
        equipmentRepository.delete(link);
        LocalDateTime now = LocalDateTime.now();
        log(disposalId, "移除待确认设备", null, disposal.getStatus(), op,
                link.getEquipmentType() + " " + link.getEquipmentCode(), now);
        touch(disposal, now);
        return getDetail(disposalId);
    }

    public DisposalDetail start(Long id, StartDisposalRequest request) {
        String operator = required(request.operator(), "操作值班人员");
        RawWaterDisposal before = getActiveOrReleased(id);
        if (!Dict.DISPOSAL_PENDING.equals(before.getStatus())) {
            throw new BizException("只有「待处置」可以进入执行，当前状态：" + before.getStatus());
        }
        String error = self.precheckProgress(id, "进入执行中");
        if (error != null) {
            throw new BizException(error);
        }
        return self.completeStart(id, operator);
    }

    @Transactional
    public String precheckProgress(Long disposalId, String action) {
        List<DisposalEquipment> links = equipmentRepository
                .findByDisposalIdOrderByEquipmentTypeAscIdAsc(disposalId);
        LocalDateTime now = LocalDateTime.now();
        List<DisposalEquipment> activeLinks = links.stream()
                .filter(l -> Dict.isActiveLinkStatus(l.getStatus()))
                .sorted(equipmentLockOrder())
                .toList();
        for (DisposalEquipment link : activeLinks) {
            String currentStatus = lockAndReadEquipmentStatus(link);
            if (!"运行".equals(currentStatus) && !Dict.LINK_CONFLICT.equals(link.getStatus())) {
                markConflict(link, currentStatus, now);
                RawWaterDisposal disposal = disposalRepository.findById(disposalId).orElse(null);
                if (disposal != null) {
                    touch(disposal, now);
                    log(disposalId, "设备冲突", null, disposal.getStatus(), "系统",
                            link.getEquipmentCode() + " 在推进前核对为「" + currentStatus + "」", now);
                }
                return link.getEquipmentCode() + " 已转为「" + currentStatus
                        + "」，冲突已记录；请改选可用设备或填写排除原因后再" + action;
            }
        }

        List<DisposalEquipment> latest = equipmentRepository
                .findByDisposalIdOrderByEquipmentTypeAscIdAsc(disposalId);
        long conflicts = latest.stream().filter(l -> Dict.LINK_CONFLICT.equals(l.getStatus())).count();
        if (conflicts > 0) {
            return "存在 " + conflicts + " 个未处理设备冲突，必须改选可用设备或填写排除原因后才能" + action;
        }
        for (DisposalEquipment link : latest) {
            if (!Dict.isActiveLinkStatus(link.getStatus())) {
                continue;
            }
            String currentStatus = currentEquipmentStatus(link);
            if (!"运行".equals(currentStatus)) {
                return link.getEquipmentCode() + " 当前为「" + currentStatus
                        + "」，请先处理设备冲突后再" + action;
            }
        }
        long pending = latest.stream().filter(l -> Dict.LINK_PENDING.equals(l.getStatus())).count();
        if (pending > 0) {
            return "存在 " + pending + " 个待确认设备，请确认或移除后再" + action;
        }
        long tanks = latest.stream()
                .filter(l -> Dict.EQUIPMENT_TANK.equals(l.getEquipmentType())
                        && Dict.isEffectiveLinkStatus(l.getStatus()))
                .count();
        long filters = latest.stream()
                .filter(l -> Dict.EQUIPMENT_FILTER.equals(l.getEquipmentType())
                        && Dict.isEffectiveLinkStatus(l.getStatus()))
                .count();
        if (tanks == 0 || filters == 0) {
            return action + "前至少要确认一个「运行」中的沉淀池和一个「运行」中的滤池";
        }
        return null;
    }

    @Transactional
    public DisposalDetail completeStart(Long id, String operator) {
        RawWaterDisposal disposal = lockDisposal(id);
        if (!Dict.DISPOSAL_PENDING.equals(disposal.getStatus())) {
            throw new ConflictException("处置状态已变化，请刷新后重试");
        }
        validateEffectiveEquipment(id, "进入执行中");
        if (disposal.getActiveSourceName() == null
                || !disposal.getActiveSourceName().equals(disposal.getSourceName())) {
            throw new ConflictException("该水源的活动处置状态已变化，请刷新后重试");
        }
        LocalDateTime now = LocalDateTime.now();
        String from = disposal.getStatus();
        disposal.setStatus(Dict.DISPOSAL_EXECUTING);
        disposal.setStartedAt(now);
        touch(disposal, now);
        log(id, "开始执行", from, Dict.DISPOSAL_EXECUTING, operator,
                "沉淀池、滤池确认完成，开始联动处置", now);
        return getDetail(id);
    }

    public DisposalDetail release(Long id, ReleaseDisposalRequest request) {
        String operator = required(request.operator(), "操作值班人员");
        String conclusion = required(request.conclusion(), "处理结论");
        if (conclusion.length() > 500) {
            throw new BizException("处理结论不能超过 500 字");
        }
        RawWaterDisposal before = getActiveOrReleased(id);
        if (!Dict.DISPOSAL_EXECUTING.equals(before.getStatus())) {
            throw new BizException("只有「执行中」可以解除，当前状态：" + before.getStatus());
        }
        String error = self.precheckProgress(id, "解除");
        if (error != null) {
            throw new BizException(error);
        }
        return self.completeRelease(id, operator, conclusion);
    }

    @Transactional
    public DisposalDetail completeRelease(Long id, String operator, String conclusion) {
        RawWaterDisposal disposal = lockDisposal(id);
        if (!Dict.DISPOSAL_EXECUTING.equals(disposal.getStatus())) {
            throw new ConflictException("处置状态已变化，请刷新后重试");
        }
        validateEffectiveEquipment(id, "解除");
        if (disposal.getActiveSourceName() == null
                || !disposal.getActiveSourceName().equals(disposal.getSourceName())) {
            throw new ConflictException("该水源的活动处置状态已变化，请刷新后重试");
        }
        LocalDateTime now = LocalDateTime.now();
        String from = disposal.getStatus();
        disposal.setStatus(Dict.DISPOSAL_RELEASED);
        disposal.setActiveSourceName(null);
        disposal.setConclusion(conclusion);
        disposal.setReleaseOperator(operator);
        disposal.setReleasedAt(now);
        touch(disposal, now);
        log(id, "解除处置", from, Dict.DISPOSAL_RELEASED, operator, conclusion, now);
        return getDetail(id);
    }

    @Transactional
    public DisposalDetail refreshConflicts(Long id, String operator) {
        String op = required(operator, "操作值班人员");
        RawWaterDisposal before = getActiveOrReleased(id);
        if (!Dict.isActiveDisposalStatus(before.getStatus())) {
            return getDetail(id);
        }
        List<DisposalEquipment> links = equipmentRepository
                .findByDisposalIdOrderByEquipmentTypeAscIdAsc(id);
        links.stream()
                .filter(l -> Dict.isActiveLinkStatus(l.getStatus()))
                .sorted(equipmentLockOrder())
                .forEach(this::lockAndReadEquipmentStatus);
        RawWaterDisposal disposal = disposalRepository.findLockedById(id)
                .orElseThrow(() -> new BizException("联动处置不存在：" + id));
        LocalDateTime now = LocalDateTime.now();
        boolean changed = false;
        for (DisposalEquipment link : links) {
            if (!Dict.isActiveLinkStatus(link.getStatus())) {
                continue;
            }
            String currentStatus = currentEquipmentStatus(link);
            if (!"运行".equals(currentStatus)) {
                changed |= markConflict(link, currentStatus, now);
            }
        }
        if (changed) {
            touch(disposal, now);
            log(id, "刷新冲突", null, disposal.getStatus(), op, "已重新核对关联设备状态", now);
        }
        return getDetail(id);
    }

    // ---------------- 处置内加药 ----------------

    public DosingResult registerDosing(Long disposalId, RegisterDosingRequest request) {
        String operator = required(request.operator(), "操作值班人员");
        String requestId = required(request.requestId(), "加药动作幂等键");
        if (requestId.length() > 64) {
            throw new BizException("加药动作幂等键不能超过 64 字");
        }

        Optional<Dosing> duplicate = dosingRepository.findByRequestId(requestId);
        if (duplicate.isPresent()) {
            Dosing existing = duplicate.get();
            if (!disposalId.equals(existing.getDisposalId())) {
                throw new ConflictException("该加药动作已属于其他处置或记录");
            }
            return DosingResult.existing(existing);
        }
        try {
            Dosing dosing = self.registerDosingTransactional(disposalId, request, operator, requestId);
            return DosingResult.created(dosing);
        } catch (DataIntegrityViolationException e) {
            Dosing existing = dosingRepository.findByRequestId(requestId).orElse(null);
            if (existing == null) {
                throw e;
            }
            if (!disposalId.equals(existing.getDisposalId())) {
                throw new ConflictException("该加药动作已属于其他处置或记录");
            }
            return DosingResult.existing(existing);
        }
    }

    @Transactional
    public Dosing registerDosingTransactional(Long disposalId, RegisterDosingRequest request,
                                              String operator, String requestId) {
        RawWaterDisposal disposal = lockDisposal(disposalId);
        if (!Dict.isActiveDisposalStatus(disposal.getStatus())) {
            throw new BizException("已解除处置不能再登记加药");
        }
        if (!Dict.isValidChemName(request.chemName())) {
            throw new BizException("药剂必须为 聚合氯化铝 / 次氯酸钠");
        }
        if (request.doseMgPerL() == null || request.doseMgPerL().signum() <= 0) {
            throw new BizException("投加量必须大于 0（mg/L）");
        }
        String targetTank = required(request.targetTank(), "目标工段");
        if (targetTank.length() > 32) {
            throw new BizException("目标工段不能超过 32 字");
        }

        LocalDateTime now = LocalDateTime.now();
        Dosing dosing = new Dosing();
        dosing.setChemName(request.chemName().trim());
        dosing.setDoseMgPerL(request.doseMgPerL());
        dosing.setTargetTank(targetTank);
        dosing.setDosedAt(request.dosedAt() == null ? now : request.dosedAt());
        dosing.setNote(request.note());
        dosing.setDisposalId(disposalId);
        dosing.setRequestId(requestId);
        dosing.setCreatedAt(now);
        dosingRepository.saveAndFlush(dosing);

        log(disposalId, "登记加药", null, disposal.getStatus(), operator,
                dosing.getChemName() + " " + dosing.getDoseMgPerL() + " mg/L → " + targetTank, now);
        touch(disposal, now);
        return dosing;
    }

    // ---------------- 设备状态流转调用的冲突标记 ----------------

    /**
     * 沉淀池已在调用方加行锁后转入非运行状态。这里先锁处置单，再把仍有效的关联标成冲突。
     */
    @Transactional
    public void markTankConflict(SedimentationTank tank, String targetStatus) {
        List<DisposalEquipment> links = equipmentRepository
                .findByEquipmentTypeAndEquipmentIdAndStatusInOrderById(
                        Dict.EQUIPMENT_TANK, tank.getId(), activeStatuses());
        for (DisposalEquipment link : links) {
            RawWaterDisposal disposal = disposalRepository.findLockedById(link.getDisposalId())
                    .orElse(null);
            if (disposal == null || !Dict.isActiveDisposalStatus(disposal.getStatus())) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            boolean changed = markConflict(link, targetStatus, now);
            if (changed) {
                touch(disposal, now);
                log(disposal.getId(), "设备冲突", null, disposal.getStatus(), "系统",
                        tank.getTankCode() + " 在处置期间转入「" + targetStatus + "」", now);
            }
        }
    }

    /** 滤池已在调用方加行锁后转入反洗状态。 */
    @Transactional
    public void markFilterConflict(Filter filter, String targetStatus) {
        List<DisposalEquipment> links = equipmentRepository
                .findByEquipmentTypeAndEquipmentIdAndStatusInOrderById(
                        Dict.EQUIPMENT_FILTER, filter.getId(), activeStatuses());
        for (DisposalEquipment link : links) {
            RawWaterDisposal disposal = disposalRepository.findLockedById(link.getDisposalId())
                    .orElse(null);
            if (disposal == null || !Dict.isActiveDisposalStatus(disposal.getStatus())) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            boolean changed = markConflict(link, targetStatus, now);
            if (changed) {
                touch(disposal, now);
                log(disposal.getId(), "设备冲突", null, disposal.getStatus(), "系统",
                        filter.getFilterCode() + " 在处置期间转入「" + targetStatus + "」", now);
            }
        }
    }

    // ---------------- 内部规则 ----------------

    private void validateEffectiveEquipment(Long disposalId, String action) {
        List<DisposalEquipment> latest = equipmentRepository
                .findByDisposalIdOrderByEquipmentTypeAscIdAsc(disposalId);
        long conflicts = latest.stream().filter(l -> Dict.LINK_CONFLICT.equals(l.getStatus())).count();
        if (conflicts > 0) {
            throw new ConflictException("设备状态已变化，存在未处理冲突，请刷新后处理");
        }
        long pending = latest.stream().filter(l -> Dict.LINK_PENDING.equals(l.getStatus())).count();
        if (pending > 0) {
            throw new ConflictException("设备确认状态已变化，请刷新后重试");
        }
        long tanks = latest.stream()
                .filter(l -> Dict.EQUIPMENT_TANK.equals(l.getEquipmentType())
                        && Dict.isEffectiveLinkStatus(l.getStatus()))
                .count();
        long filters = latest.stream()
                .filter(l -> Dict.EQUIPMENT_FILTER.equals(l.getEquipmentType())
                        && Dict.isEffectiveLinkStatus(l.getStatus()))
                .count();
        if (tanks == 0 || filters == 0) {
            throw new ConflictException("有效沉淀池或滤池数量已变化，不能" + action);
        }
    }

    private boolean markConflict(DisposalEquipment link, String currentEquipmentStatus, LocalDateTime now) {
        String reason = "设备在处置期间转为「" + currentEquipmentStatus + "」，原关联暂停生效";
        if (Dict.LINK_CONFLICT.equals(link.getStatus()) && reason.equals(link.getConflictReason())) {
            return false;
        }
        link.setStatus(Dict.LINK_CONFLICT);
        link.setConflictReason(reason);
        link.setUpdatedAt(now);
        equipmentRepository.save(link);
        return true;
    }

    private void saveNewLink(Long disposalId, String type, Long equipmentId, String code,
                             String status, String operator, LocalDateTime now) {
        DisposalEquipment link = new DisposalEquipment();
        link.setDisposalId(disposalId);
        link.setEquipmentType(type);
        link.setEquipmentId(equipmentId);
        link.setEquipmentCode(code);
        link.setStatus(status);
        link.setOperator(operator);
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        equipmentRepository.saveAndFlush(link);
    }

    private String lockAndReadEquipmentStatus(DisposalEquipment link) {
        if (Dict.EQUIPMENT_TANK.equals(link.getEquipmentType())) {
            SedimentationTank tank = tankRepository.findLockedById(link.getEquipmentId())
                    .orElseThrow(() -> new BizException("沉淀池已不存在：" + link.getEquipmentCode()));
            return tank.getStatus();
        }
        Filter filter = filterRepository.findLockedById(link.getEquipmentId())
                .orElseThrow(() -> new BizException("滤池已不存在：" + link.getEquipmentCode()));
        return filter.getStatus();
    }

    private String currentEquipmentStatus(DisposalEquipment link) {
        if (Dict.EQUIPMENT_TANK.equals(link.getEquipmentType())) {
            return tankRepository.findById(link.getEquipmentId())
                    .map(SedimentationTank::getStatus)
                    .orElse("已删除");
        }
        return filterRepository.findById(link.getEquipmentId())
                .map(Filter::getStatus)
                .orElse("已删除");
    }

    private DisposalSummary toSummary(RawWaterDisposal d) {
        List<DisposalEquipment> links = equipmentRepository
                .findByDisposalIdOrderByEquipmentTypeAscIdAsc(d.getId());
        int dosingCount = dosingRepository.findByDisposalIdOrderByDosedAtDescIdDesc(d.getId()).size();
        return new DisposalSummary(
                d.getId(), d.getRawWaterId(), d.getSourceName(), d.getStatus(), d.getAssignee(),
                d.getCreatedAt(), d.getStartedAt(), d.getReleasedAt(), d.getConclusion(),
                d.getReleaseOperator(),
                countByType(links, Dict.EQUIPMENT_TANK, null),
                countByType(links, Dict.EQUIPMENT_FILTER, null),
                countByType(links, Dict.EQUIPMENT_TANK, Dict.LINK_CONFIRMED),
                countByType(links, Dict.EQUIPMENT_FILTER, Dict.LINK_CONFIRMED),
                (int) links.stream().filter(l -> Dict.LINK_CONFLICT.equals(l.getStatus())).count(),
                dosingCount);
    }

    private DisposalEquipmentView toEquipmentView(DisposalEquipment link, String currentStatus) {
        return new DisposalEquipmentView(
                link.getId(), link.getEquipmentType(), link.getEquipmentId(), link.getEquipmentCode(),
                link.getStatus(), currentStatus, link.getConflictReason(), link.getExcludedReason(),
                link.getOperator(), link.getResolvedBy(), link.getResolvedAt(),
                link.getCreatedAt(), link.getUpdatedAt());
    }

    private DisposalFlowLogView toFlowView(DisposalFlowLog log) {
        return new DisposalFlowLogView(log.getId(), log.getAction(), log.getFromStatus(),
                log.getToStatus(), log.getOperator(), log.getDetail(), log.getCreatedAt());
    }

    private int countByType(List<DisposalEquipment> links, String type, String status) {
        return (int) links.stream()
                .filter(l -> type.equals(l.getEquipmentType()))
                .filter(l -> status == null || status.equals(l.getStatus()))
                .count();
    }

    private void log(Long disposalId, String action, String from, String to,
                     String operator, String detail, LocalDateTime now) {
        DisposalFlowLog log = new DisposalFlowLog();
        log.setDisposalId(disposalId);
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperator(operator);
        log.setDetail(detail);
        log.setCreatedAt(now);
        flowLogRepository.save(log);
    }

    private void touch(RawWaterDisposal disposal, LocalDateTime now) {
        disposal.setUpdatedAt(now);
        disposalRepository.save(disposal);
    }

    private RawWaterDisposal getActiveOrReleased(Long id) {
        return disposalRepository.findById(id)
                .orElseThrow(() -> new BizException("联动处置不存在：" + id));
    }

    private RawWaterDisposal lockDisposal(Long id) {
        return disposalRepository.findLockedById(id)
                .orElseThrow(() -> new BizException("联动处置不存在：" + id));
    }

    private void requireActive(RawWaterDisposal disposal) {
        if (!Dict.isActiveDisposalStatus(disposal.getStatus())) {
            throw new BizException("处置单已解除，不能继续该操作");
        }
    }

    private ConflictException takeover(RawWaterDisposal d) {
        String message = "该水源已有未解除联动处置，已被「" + d.getAssignee() + "」接管（单号 #"
                + d.getId() + "，状态：" + d.getStatus() + "）";
        return new ConflictException(message, new com.waterworks.plant.dto.DisposalTakeoverView(
                d.getId(), d.getSourceName(), d.getStatus(), d.getAssignee(), message));
    }

    private List<String> activeStatuses() {
        return List.of(Dict.LINK_PENDING, Dict.LINK_CONFIRMED, Dict.LINK_CONFLICT);
    }

    private Comparator<DisposalEquipment> equipmentLockOrder() {
        return Comparator.comparing(DisposalEquipment::getEquipmentType)
                .thenComparing(DisposalEquipment::getEquipmentId);
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        List<Long> result = ids.stream().filter(java.util.Objects::nonNull).toList();
        if (new HashSet<>(result).size() != result.size()) {
            throw new BizException("同一设备不能重复选择");
        }
        return result;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(field + "不能为空");
        }
        return value.trim();
    }
}
