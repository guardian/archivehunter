import React, {ChangeEvent} from "react";
import {makeStyles, TextField} from "@material-ui/core";
import Autocomplete from "@material-ui/lab/Autocomplete";

const useStyles = makeStyles({
    deptBox: {
        marginTop: 0,
        marginBottom: 0,
        width: "95%"
    }
});

interface DepartmentSelectorProps {
    knownDepartments: string[];
    value: string;
    onChange: (evt:ChangeEvent< {} >, newValue:string|null)=>void;
}
const DepartmentSelector:React.FC<DepartmentSelectorProps> = (props) => {
    const classes = useStyles();

    return <Autocomplete id="department-selector" freeSolo
                         className={classes.deptBox}
                         options={props.knownDepartments}
                         renderInput={ (renderParams)=>
                             <TextField {...renderParams} label="Department" margin="normal" variant="outlined"/>
                         }
                         value={props.value}
                         onChange={props.onChange}
    />
}

export default DepartmentSelector;