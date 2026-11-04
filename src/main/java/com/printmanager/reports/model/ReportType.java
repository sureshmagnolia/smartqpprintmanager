package com.printmanager.reports.model;

/**
 * Metadata and statutory defaults for the 10+ ExamFlow reports.
 */
public enum ReportType {
    QP_REQUIREMENT(
        "qpaper_report",
        "1. Question Paper Report",
        "Stream-wise summary of question papers and candidate counts needed for the session.",
        false, // Simplex
        "Session-wise",
        "generate-qpaper-report-button"
    ),
    QP_DISTRIBUTION(
        "qp_distribution_report",
        "2. QP Distribution by QP-Code Report",
        "Room-by-room count checklist per QP code for envelope packing counters.",
        false, // Simplex
        "Session-wise",
        "generate-qp-distribution-report-button"
    ),
    ROOM_WISE_SEATING(
        "room_wise_seating",
        "3. Room-wise Seating Report (Signing Sheet)",
        "Hall nominal roll with desk numbers, candidate signatures, and invigilator verification.",
        true, // Duplex (Statutory requirement: single leaf per hall to prevent page loss)
        "Room-wise",
        "generate-report-button"
    ),
    CANDIDATE_DAYWISE_SEATING(
        "candidate_daywise_seating",
        "4. Seating Details for Candidates (Notice Board)",
        "High-density multi-column register number to room mapping posted on notice boards.",
        false, // Strictly Simplex (pasted on walls/boards)
        "Session-wise",
        "generate-daywise-report-button"
    ),
    ROOM_STICKERS(
        "room_stickers",
        "5. Room Stickers (Door Slips - 2 per Page)",
        "Cut-and-paste classroom door placards showing room name, capacity, and register number ranges.",
        false, // Strictly Simplex (pasted on doors)
        "Room-wise",
        "generate-sticker-button"
    ),
    SCRIBE_PROFORMA(
        "scribe_proforma",
        "6. Scribe Proforma (One Page Per Scribe)",
        "Official university declaration and undertaking certificate per scribe candidate.",
        false, // Simplex (1 page per scribe dossier)
        "Scribe-wise",
        "generate-scribe-proforma-button"
    ),
    SCRIBE_ASSISTANCE(
        "scribe_assistance",
        "7. Scribe Assistance Report",
        "Summary roster of all scribe candidates, assigned scribes, allocated rooms, and extra time.",
        false, // Simplex
        "Session-wise",
        "generate-scribe-report-button"
    ),
    INVIGILATOR_REQUIREMENT(
        "invigilator_requirement",
        "8. Invigilator Requirement Summary",
        "Duty roster and key/packet handover sign-in ledger for active invigilators.",
        false, // Simplex
        "Session-wise",
        "generate-invigilator-report-button"
    ),
    ROOM_ALLOTMENT_SUMMARY(
        "room_allotment_summary",
        "9. Room Allotment Summary",
        "Executive one-page abstract of all active rooms, counts, and assigned invigilators for Observers.",
        false, // Simplex
        "Session-wise",
        "generate-room-summary-button"
    ),
    SCRIBE_MASTER(
        "scribe_master",
        "10. Scribe Assistance Master Report",
        "Cumulative multi-session audit ledger for university remuneration and external audit records.",
        true, // Duplex (multi-page bound administrative ledger)
        "Multi-session",
        "generate-scribe-master-report-button"
    ),
    ABSENTEE_STATEMENT(
        "absentee_statement",
        "11. Absentee Statement Proforma",
        "Hall absentee statement attached to answer booklet bundles dispatched to valuation camp.",
        false, // Simplex
        "Room-wise",
        "generate-absentee-report-button"
    );

    private final String id;
    private final String displayName;
    private final String description;
    private final boolean defaultDuplex;
    private final String scope;
    private final String examFlowButtonId;

    ReportType(String id, String displayName, String description, boolean defaultDuplex, String scope, String examFlowButtonId) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.defaultDuplex = defaultDuplex;
        this.scope = scope;
        this.examFlowButtonId = examFlowButtonId;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public boolean isDefaultDuplex() { return defaultDuplex; }
    public String getScope() { return scope; }
    public String getExamFlowButtonId() { return examFlowButtonId; }

    public static ReportType fromId(String id) {
        for (ReportType type : values()) {
            if (type.id.equalsIgnoreCase(id) || type.name().equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }
}

