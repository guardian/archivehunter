import React from 'react';
import {FormControlLabel, makeStyles, Paper, Radio, RadioGroup, Typography} from "@material-ui/core";

const useStyles = makeStyles({
    container: {
        padding: "1em",
        marginTop: "0.6em"
    }
});

interface InitiateAddComponentProps {
    searchModeUpdated: (willSearch:"search"|"entry")=>void;
    searchMode: "search"|"entry";
}

const InitiateAddComponent:React.FC<InitiateAddComponentProps> = (props) => {
    const classes = useStyles();

    return <div>
        <Typography variant="h5">Add proxy framework</Typography>
        <Paper elevation={3} className={classes.container}>
            <RadioGroup>
                <FormControlLabel control={<Radio checked={props.searchMode=="search"} onClick={()=>props.searchModeUpdated("search")}/>} label="Search Cloudformation for deployments"/>
                <FormControlLabel control={<Radio checked={props.searchMode=="entry"} onClick={()=>props.searchModeUpdated("entry")}/>} label="Specify details manually"/>
            </RadioGroup>
        </Paper>
    </div>;
}

export default InitiateAddComponent;