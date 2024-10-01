package com.wizardform.api.helper;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class QueryParams {

    private String searchTerm = "";
    private String sortField = "";
    @Pattern(regexp = "ascending|descending", message = "Sort direction must be 'ascending' or 'descending'")
    private String sortDirection = "ascending";     // default sorting direction is ascending
    @Min(value = 1, message = "Page number should be >= 1")
    @Max(value = Integer.MAX_VALUE, message = "Page number should be under max integer limit")
    private int pageNumber = 1;
    @Min(value = 5, message = "Page size should be >= 5")
    @Max(value = 20, message = "Page size should be <= 20")
    private int pageSize = 5;   // default page size is 5 records per page
}
