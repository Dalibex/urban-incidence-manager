package es.uma.urbanpulse.analytics.service;

import es.uma.urbanpulse.analytics.dto.IncidentReportRow;
import es.uma.urbanpulse.analytics.repository.OperationalReportQuery;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operational reports of incidents (RF19, #42): the incidents reported in a period, counted with
 * their current status and grouped. The role is checked by the controller; the report holds no
 * personal data, so every analyst and administrator gets the same one.
 */
@Service
@Transactional(readOnly = true)
public class OperationalReportService {

    private final OperationalReportQuery query;

    public OperationalReportService(OperationalReportQuery query) {
        this.query = query;
    }

    /**
     * The groups with incidents in the period. Status and priority go in their declared order, the
     * names alphabetically, and the group that is not available ({@code null}) last.
     */
    public List<IncidentReportRow> report(ReportGrouping grouping, ReportPeriod period) {
        return query.groups(grouping, period).stream().sorted(order(grouping)).toList();
    }

    static Comparator<IncidentReportRow> order(ReportGrouping grouping) {
        Comparator<String> groups =
                switch (grouping) {
                    case STATUS -> Comparator.comparing(IncidentStatus::valueOf);
                    case PRIORITY -> Comparator.comparing(Priority::valueOf);
                    // A collator puts "Árboles" next to "Arbustos" and "waste" next to "Waste",
                    // where a plain String comparison would send accents and lowercase last.
                    case CATEGORY, DEPARTMENT, DISTRICT -> alphabetical();
                };
        return Comparator.comparing(IncidentReportRow::group, Comparator.nullsLast(groups));
    }

    private static Comparator<String> alphabetical() {
        var collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.SECONDARY);
        // Two names that differ only in case compare equal; the plain comparison breaks the tie,
        // so the order never depends on the order the database returned them in.
        return ((Comparator<String>) collator::compare).thenComparing(Comparator.naturalOrder());
    }
}
