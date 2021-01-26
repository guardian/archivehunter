import React from "react";
import {Button, Grid, Paper, Tooltip, Typography} from "@material-ui/core";
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import axios from "axios";
import ErrorViewComponent from "../common/ErrorViewComponent";

interface ScanTargetActionsBoxProps {
    idToLoad: string;
    actionDidStart: (caption:string)=>void;
    actionDidFail: (caption:string)=>void;
    actionDidSucceed: (caption:string)=>void;
    classes: Record<string, string>;
    bucketName:string;
}

const ScanTargetActionsBox:React.FC<ScanTargetActionsBoxProps> = (props)=>{
    const triggerAddedScan = ()=>{
        return generalScanTrigger("additionScan")
    }
    const triggerRemovedScan = ()=>{
        return generalScanTrigger("deletionScan")
    }
    const triggerFullScan = () =>{
        return generalScanTrigger("scan")
    }

    const triggerValidateConfig = async () => {
        const targetId = props.idToLoad;
        props.actionDidStart("Validating config...")
        try {
            const result = await axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "checkTranscoder")
            console.log("Config validation has been started with job ID " + result.data.entity);
        } catch(err) {
            console.error(err);
            props.actionDidFail(ErrorViewComponent.formatError(err, false));
        }
    }

    const triggerTranscodeSetup = async ()=>{
        const targetId = props.idToLoad;
        try {
            const result = await axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "createPipelines?force=true")
            props.actionDidStart("Transcode setup has been started with job ID " + result.data.entity);
        } catch(err) {
            props.actionDidFail(ErrorViewComponent.formatError(err, false))
        }
    }

    const triggerProxyGen = async ()=>{
        const targetId = props.idToLoad;
        try {
            await axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "genProxies");
            props.actionDidStart("Proxy generation has started on this target")
        } catch (err) {
            console.error(err);
            props.actionDidFail(ErrorViewComponent.formatError(err, false));
        }
    }

    const triggerProxyRelink = async ()=>{
        const targetId = props.idToLoad;
        try {
            await axios.post("/api/proxy/relink/" + props.bucketName)
            props.actionDidStart("Global proxy relink has been started");
        } catch(err) {
            console.error(err);
            props.actionDidFail(ErrorViewComponent.formatError(err, false))
        }
    }

    const generalScanTrigger = async (type:string)=>{
        const targetId = props.idToLoad;

        try {
            await axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + type)
            props.actionDidStart("Rescan of this bucket has been triggered")
        } catch (err) {
            props.actionDidFail(ErrorViewComponent.formatError(err, false))
        }
    }

    return <Paper elevation={3} className={props.classes.formContainer}>
        <Typography variant="h4">Actions</Typography>
        <Grid container justify="space-between" spacing={1} className={props.classes.actionButtonsContainer}>
            <Grid item>
                <Tooltip title="Addition scan">
                    <Button variant="outlined" startIcon={<FontAwesomeIcon icon="folder-plus"/>} onClick={triggerAddedScan}>
                        Scan for added files only
                    </Button>
                </Tooltip>
            </Grid>
            <Grid item>
                <Tooltip title="Removal scan">
                    <Button variant="outlined"
                            startIcon={<FontAwesomeIcon icon="folder-minus"/>}
                            onClick={triggerRemovedScan}>
                        Scan for removed files
                    </Button>
                </Tooltip>
            </Grid>
            <Grid item>
                <Tooltip title="Full scan">
                    <Button variant="outlined"
                            startIcon={<FontAwesomeIcon icon="folder"/>}
                            onClick={triggerFullScan}>
                        Scan for added and removed files
                    </Button>
                </Tooltip>
            </Grid>
            <Grid item>
                <Tooltip title="Proxy generation">
                    <Button variant="outlined"
                            startIcon={<FontAwesomeIcon icon="compress-arrows-alt"/>}
                            onClick={triggerProxyGen}>
                        Generate proxies
                    </Button>
                </Tooltip>
            </Grid>
            <Grid item>
                <Tooltip title="Relink proxies">
                    <Button variant="outlined"
                            startIcon={<FontAwesomeIcon icon="book-reader"/>}
                            onClick={triggerProxyRelink}>
                        Relink existing proxies
                    </Button>
                </Tooltip>
            </Grid>
        </Grid>
    </Paper>
}

export default ScanTargetActionsBox;