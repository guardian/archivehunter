import React from "react";
import {makeStyles, Paper, Typography} from "@material-ui/core";

interface EnterDeploymentComponentProps {

}

const useStyles = makeStyles((theme)=>({
    errorText: {
        color: theme.palette.error.dark
    }
}));

const EnterDeploymentComponent:React.FC<EnterDeploymentComponentProps> = (props) => {
    const classes = useStyles();

    return <>
        <Typography variant="h5">Enter deployment details manually</Typography>
        <Paper elevation={3}>
            <Typography className={classes.errorText}>Not implemented yet</Typography>
        </Paper>
        </>
}

export default EnterDeploymentComponent;