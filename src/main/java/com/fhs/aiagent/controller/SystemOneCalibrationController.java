package com.fhs.aiagent.controller;

import com.fhs.aiagent.evaluation.SystemOneCalibrationCase;
import com.fhs.aiagent.evaluation.SystemOneCalibrationReport;
import com.fhs.aiagent.evaluation.SystemOneCalibrationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/agent-evaluation/system-one-calibration")
@ConditionalOnProperty(name = "agent.evaluation.api-enabled", havingValue = "true")
public class SystemOneCalibrationController {

    private final SystemOneCalibrationService service;

    public SystemOneCalibrationController(SystemOneCalibrationService service) {
        this.service = service;
    }

    @GetMapping("/cases")
    public List<SystemOneCalibrationCase> cases() {
        return service.loadCases();
    }

    @PostMapping("/runs")
    public SystemOneCalibrationReport run() {
        return service.evaluate();
    }
}
