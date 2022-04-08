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
        width: "24px",
        verticalAlign: "bottom",
        marginRight: "0.4em"
    },
    extraMargin: {
        marginLeft: "0.4em"
    },
    warning: {
        color: theme.palette.warning.main
    },
    ok: {
        color: theme.palette.success.main
    }
}))

const MonitoringSetupCheck:React.FC<MonitoringSetupCheckProps> = (props) => {
    const [loading, setLoading] = useState(true);
    const [needsAttention, setNeedsAttention] = useState(false);
    const [lastError, setLastError] = useState("");

    const classes = useStyles();

    const request = async (testOnly:boolean)=> {
        setLoading(true);
        try {
            const url = `/api/scanTarget/${encodeURIComponent(props.scanTarget)}/monitoringConfiguration`;
            const response = testOnly ?
                await axios.get<MonitoringConfigurationResponse>(url) :
                await axios.post<MonitoringConfigurationResponse>(url);

            setLastError("");
            if(testOnly) {
                setLoading(false);
                setNeedsAttention(response.data.updatesRequired);
            } else {
                window.setTimeout(()=>request(true), 800);  //if we updated the configuration, refresh after a short delay
            }

        } catch(err) {
            console.error(`Could not get monitoring configuration for scan target ${encodeURIComponent(props.scanTarget)}: `, err);
            setLoading(false);
            setLastError("Could not check monitoring configuration, consult the browser console logs for more information");
            setNeedsAttention(false);
        }
    }

    const updateInfo = async ()=> request(true);

    const fixConfig = async ()=>request(false);

    useEffect(()=>{
        if(props.scanTarget) updateInfo();
    }, [props.scanTarget]);

    return (
        <Grid container spacing={3} alignItems="baseline">
            {
                loading ? <Grid item className={classes.extraMargin}><CircularProgress className={classes.inlineIcon}/></Grid> : undefined
            }
            {
                needsAttention && !lastError ? <>
                    <Grid item className={classes.extraMargin}>
                        <WarningRounded className={clsx(classes.warning, classes.inlineIcon)}/>
                        <Typography style={{display: "inline"}}>Configuration is not valid</Typography>
                    </Grid>
                    <Grid item>
                    <Tooltip title="Try to fix">
                        <IconButton onClick={fixConfig}><Build/></IconButton>
                    </Tooltip>
                    </Grid>
                </> : !lastError ? <>
                    <Grid item className={classes.extraMargin}><CheckCircle className={clsx(classes.ok, classes.inlineIcon)}/></Grid>
                    <Grid item><Typography>Configuration is valid</Typography></Grid>
                </> : undefined
            }
            {
                lastError ? <Grid item className={classes.extraMargin}><Typography>{lastError}</Typography></Grid> : undefined
            }
            {
                <Grid item>
                    <Tooltip title="Check again">
                        <IconButton onClick={updateInfo}><Refresh/></IconButton>
                    </Tooltip>
                </Grid>
            }
        </Grid>
    )
}

export default MonitoringSetupCheck;