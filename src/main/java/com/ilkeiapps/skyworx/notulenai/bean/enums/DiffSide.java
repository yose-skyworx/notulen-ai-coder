package com.ilkeiapps.skyworx.notulenai.bean.enums;

/**
 * Which side of a diff a finding refers to. {@code NEW} is validated against the snapshot at the
 * selected commit; {@code OLD} against the snapshot at its parent, which is the only correct reference
 * for deleted code.
 */
public enum DiffSide {

	NEW, OLD
}
