import React from "react";
import {
    FormControlLabel,
    Grid,
    makeStyles,
    MenuItem,
    Radio,
    RadioGroup,
    Select,
    Typography,
    Input
} from "@material-ui/core";

interface BrowseFilterProps {
    filterString: string;
    filterStringChanged: (newString:string)=>void;
}

const useStyles = makeStyles({
    selector: {
        width: "100%"
    }
});

const BrowseFilter:React.FC<BrowseFilterProps> = (props) => {
    const classes = useStyles();

    return <Grid container direction="column" >
        <Grid item>
            <Typography variant="h6">Filter</Typography>
        </Grid>
        <Grid item>
            <Input id="filter-input" value={props.filterString} onChange={(evt) => props.filterStringChanged(evt.target.value as string)} />
        </Grid>
    </Grid>
}

export default BrowseFilter;