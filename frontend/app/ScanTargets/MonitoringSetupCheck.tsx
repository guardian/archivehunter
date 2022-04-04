import React, {useEffect, useState} from "react";
import axios from "axios";
import {MonitoringConfigurationResponse} from "../types";
import {CircularProgress, Grid, IconButton, makeStyles, Tooltip, Typography} from "@material-ui/core";
import {Build, CheckCircle, Refresh, WarningRounded} from "@material-ui/icons";
import clsx from "clsx";

interface MonitoringSetupCheckProps {
    scanTarget:string;
}

const useStyles = makeStyles((theme)=>({
    inlineIcon: {
        height: "24px",
        marginRight: "0.4em"
    },
    warning: {
        color: theme.palette.warning.main
    },
    ok: {
        color: theme.palette.info.main
    }
}))

const MonitoringSetupCheck:React.FC<MonitoringSetupCheckProps> = (props) => {
    const [loading, setLoading] = useState(true);
    const [needsAttention, setNeedsAttention] = useState(false);
    const [lastError, setLastError] = useState("");

    const classes = useStyles();

    const request = async (test:boolean)=> {
        try {
            const url = `/api/scanTarget/${encodeURIComponent(props.scanTarget)}/monitoringConfiguration`;
            const response = test ?
                await axios.get<MonitoringConfigurationResponse>(url) :
                await axios.post<MonitoringConfigurationResponse>(url);

            setLoading(false);
            setNeedsAttention(response.data.updatesRequired);
        } catch(err) {
            console.error(`Could not get monitoring configuration for scan target ${encodeURIComponent(props.scanTarget)}: `, err);
            setLoading(false);
            setLastError("Could not check monitoring configuration, consult the browser console logs for more information");
        }
    }

    const updateInfo = async ()=> request(true);

    const fixConfig = async ()=>request(false);

    useEffect(()=>{
        updateInfo();
    }, [props.scanTarget]);

    return (
        <Grid container>
            {
                loading ? <Grid item><CircularProgress className={classes.inlineIcon}/></Grid> : undefined
            }
            {
                needsAttention ? <>
                    <Grid item><WarningRounded className={clsx(classes.warning, classes.inlineIcon)}/></Grid>
                    <Grid item><Typography>Configuration is not valid</Typography></Grid>
                    <Grid item>
                    <Tooltip title="Try to fix">
                        <IconButton onClick={fixConfig}><Build className={classes.inlineIcon}/></IconButton>
                    </Tooltip>
                    </Grid>
                    <Grid item>
                    <Tooltip title="Check again">
                        <IconButton onClick={updateInfo}><Refresh className={classes.inlineIcon}/></IconButton>
                    </Tooltip>
                    </Grid>
                </> : <>
                    <Grid item><CheckCircle className={clsx(classes.ok, classes.inlineIcon)}/></Grid>
                    <Grid item><Typography>Configuration is valid</Typography></Grid>
                </>
            }
            {
                lastError ? <Grid item><Typography>{lastError}</Typography></Grid> : undefined
            }
            {
                <Grid item>
                    <Tooltip title="Check again">
                        <IconButton onClick={updateInfo}><Refresh className={classes.inlineIcon}/></IconButton>
                    </Tooltip>
                </Grid>
            }
        </Grid>
    )
}

export default MonitoringSetupCheck;