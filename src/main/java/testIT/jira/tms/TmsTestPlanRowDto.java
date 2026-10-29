package testIT.jira.tms;

import java.util.ArrayList;
import java.util.List;

public class TmsTestPlanRowDto {
    public String id;
    public Long globalId;
    public Long projectGlobalId;
    public String name;
    public String url;
    public String build;
    public String productName;
    public String startDate;
    public String endDate;
    public String statusLabel;
    public String statusType;
    public TmsTestPlanCountsDto tests;
    public List<TmsPlanAttributeDto> attributes = new ArrayList<>();
    public List<TmsPlanBugDto> bugs = new ArrayList<>();
    public List<TmsPlanPointDto> points = new ArrayList<>();
    /** True when some statuses have more tests than {@code points} carries. */
    public boolean pointsTruncated;
}
