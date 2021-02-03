import React from "react";
import {FormControlLabel, FormLabel, MenuItem, Radio, RadioGroup, Select} from "@material-ui/core";
import {SortableField, SortOrder} from "../types";

interface BrowseSortOrderProps {
    sortOrder: SortOrder;
    field: SortableField;
    orderChanged: (newOrder:SortOrder)=>void;
    fieldChanged: (newField:SortableField)=>void;
}

const BrowseSortOrder:React.FC<BrowseSortOrderProps> = (props) => {
    return <>
        <FormLabel htmlFor="sort-order-selector">Sort by</FormLabel>
        <Select id="sort-order-selector" value={props.field}
                onChange={(evt) => props.fieldChanged(evt.target.value as SortableField)}>
            <MenuItem value="path">Filename</MenuItem>
            <MenuItem value="last_modified">Age</MenuItem>
            <MenuItem value="size">Size</MenuItem>
        </Select>
        <RadioGroup>
            <FormControlLabel label="Ascending"
                              control={
                                  <Radio checked={props.sortOrder == "Ascending"}
                                         onClick={() => props.orderChanged("Ascending")}/>
                              }
            />
            <FormControlLabel label="Descending"
                              control={
                                  <Radio checked={props.sortOrder == "Descending"}
                                         onClick={() => props.orderChanged("Descending")}/>
                              }
            />
        </RadioGroup>
    </>
}

export default BrowseSortOrder;