package com.ilkeiapps.skyworx.notulenai.service;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.service.FindingValidator.CandidateFinding;

import lombok.extern.slf4j.Slf4j;

/**
 * The {@link ReviewAgent} used while {@code code-review.inference.enabled=false}: it reports nothing.
 * <p>
 * This is the active implementation today, and the reason is a measured one rather than a placeholder —
 * the only chat model deployed on the inference server ({@code lfm-1.2b}) produces unusable output at
 * the context sizes a file review needs. Wiring a real agent to it would generate findings that fail
 * validation, which is worse than generating none: a wall of rejected findings looks like a broken
 * review rather than an absent one.
 * <p>
 * The orchestrator does not rely on the empty return value to know inference is off. It reads the
 * property itself and records each file as {@code SKIPPED} with reason {@code INFERENCE_DISABLED}, so a
 * run under this agent is visibly "not reviewed" rather than indistinguishable from "reviewed, nothing
 * found". Every deterministic stage still executes, which keeps the pipeline exercisable end to end.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "code-review.inference", name = "enabled", havingValue = "false",
		matchIfMissing = true)
public class DisabledReviewAgent implements ReviewAgent {

	@Override
	public List<CandidateFinding> review(ReviewTarget target) {
		log.debug("inference disabled: not reviewing {}", target.filePath());
		return List.of();
	}
}
