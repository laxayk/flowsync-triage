# Field types and transformations
plan: all
tags: field type, date format, transformation, trim, lowercase
reviewed: 2026-10-13

## Type handling
FlowSync converts text, numbers, booleans and dates between tools. Dates are sent in ISO 8601 form. Fields that cannot be converted are listed as failed rows with the reason.

## Transformations
Available transformations are trim, lowercase, uppercase, default value and concatenate. Custom scripts and formulas are not supported.

