import React from "react";
import {
    FormControlLabel,
    FormLabel,
    Grid,
    makeStyles,
    MenuItem,
    Radio,
    RadioGroup,
    Select,
    Typography
} from "@material-ui/core";
import {SortableField, SortOrder} from "../types";

interface BrowseSortOrderProps {
    sortOrder: SortOrder;
    field: SortableField;
    orderChanged: (newOrder:SortOrder)=>void;
    fieldChanged: (newField:SortableField)=>void;
}

const useStyles = makeStyles({
    selector: {
        width: "100%"
    }
});

const BrowseSortOrder:React.FC<BrowseSortOrderProps> = (props) => {
    const classes = useStyles();

    return <Grid container direction="column" >
        <Grid item>
            <Typography variant="h6">Sort by</Typography>
        </Grid>
        <Grid item>
            <Select id="sort-order-selector" value={props.field} className={classes.selector}
                    onChange={(evt) => props.fieldChanged(evt.target.value as SortableField)}>
                <MenuItem value="path">File path</MenuItem>
                <MenuItem value="last_modified">Age</MenuItem>
                <MenuItem value="size">Size</MenuItem>
            </Select>
        </Grid>
        <Grid item>
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
        </Grid>
    </Grid>
}

export default BrowseSortOrder;