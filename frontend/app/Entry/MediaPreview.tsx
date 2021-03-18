import React, {useEffect, useState} from "react";
import {MimeType, ProxyLocation, ProxyLocationsResponse, ProxyType, StylesMap} from "../types";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";
import {Button, CircularProgress, Grid, makeStyles, Typography} from "@material-ui/core";
import {baseStyles} from "../BaseStyles";
import EntryPreviewSwitcher from "./EntryPreviewSwitcher";
import clsx from "clsx";
import MediaPlayer from "./MediaPlayer";
import EntryThumbnail from "./EntryThumbnail";
import ReconnectDialog from "./ReconnectDialog";

interface MediaPreviewProps {
    onError?: (errorString:string)=>void;
    triggeredProxyGeneration?: ()=>void;
    itemId: string;
    itemName: string;
    fileExtension: string;
    mimeType: MimeType;
    className?: string;
    relinkedCb?: ()=>void;
}

const useStyles = makeStyles((theme)=>Object.assign({
    partialDivider: {
        width: "70%",
    },
    errorText: {
        color: theme.palette.error.dark,
        fontWeight: "bold"
    },
    processingText: {
        color: theme.palette.success,
        fontStyle: "italic"
    },
    thumbNote: {
        marginTop: 0,
        marginBottom: 0,
        fontStyle: "italic"
    }
} as StylesMap, baseStyles));

const MediaPreview:React.FC<MediaPreviewProps> = (props) => {
    const [proxyLocations, setProxyLocations] = useState<ProxyLocation[]>([]);
    const [loading, setLoading] = useState(true);
    const [selectedPreview, setSelectedPreview] = useState<ProxyType>("VIDEO");
    const [processMessage, setProcessMessage] = useState<string|undefined>(undefined);
    const [autoPlay, setAutoPlay] = useState(true);
    const [showingCreate, setShowingCreate] = useState(false);
    const [potentialProxies, setPotentialProxies] = useState<undefined|ProxyLocation[]>(undefined);
    const [showingReconnect, setShowingReconnect] = useState(false);

    const classes = useStyles();

    useEffect(()=>{
        const loadData = async ()=> {
            try {
                const response = await axios.get<ProxyLocationsResponse>(`/api/proxy/${props.itemId}`);
                setProxyLocations(response.data.entries);
                setLoading(false);
            } catch(err) {
                setLoading(false);
                if(props.onError) props.onError(formatError(err, false));
            }
        }

        loadData();
    }, [props.itemId]);

    const bestAvailablePreview = (proxyTypes:ProxyType[])=>{
        if(proxyTypes.includes("VIDEO")) return "VIDEO";
        if(proxyTypes.includes("AUDIO")) return "AUDIO";
        if(proxyTypes.includes("POSTER")) return "POSTER";
        if(proxyTypes.includes("THUMBNAIL")) return "THUMBNAIL";
        return "THUMBNAIL";
    }

    useEffect(()=>{
        const proxyTypes = proxyLocations.map(loc=>loc.proxyType);
        setSelectedPreview(bestAvailablePreview(proxyTypes))
    }, [proxyLocations]);

    const newTypeSelected = (newType:ProxyType)=> {
        const proxyTypes = proxyLocations.map(loc=>loc.proxyType);
        setShowingCreate(!proxyTypes.includes(newType));
        setSelectedPreview(newType)
    }

    const initiateCreateProxy = async ()=>{
        try {
        const result = await axios.post("/api/proxy/generate/" + props.itemId + "/" + selectedPreview.toLowerCase());
            const msg = result.data.entry==="disabled" ? "Proxy generation disabled for this storage" : "Proxy generation started";
            setProcessMessage(msg);
            if(props.triggeredProxyGeneration && result.data.entry!=="disabled") props.triggeredProxyGeneration();
        } catch(err) {
            console.log(err);
            setProcessMessage("Proxy generation failed, see console log");
            if(props.onError) props.onError(formatError(err, false));
        }
    }

    const initiateRelinkSearch = async ()=>{
        try {
            setProcessMessage("Searching...")
            const result = await axios.get<ProxyLocationsResponse>("/api/proxy/searchForFile?id=" + encodeURIComponent(props.itemId));
            const autoLinked = result.data.entryCount==1
            setProcessMessage(`Found ${result.data.entryCount} potential proxies${autoLinked ? ", automatically linked" : "."}`);
            if(result.data.entryCount>1) {
                setPotentialProxies(result.data.entries);
                if(props.relinkedCb) props.relinkedCb();
            }
        } catch(err) {
            console.log(err);
            setProcessMessage(formatError(err, true));
            if(props.onError) props.onError(formatError(err, false));
        }
    }

    const handleProxyNotFound = (proxyType:ProxyType) => {
        setShowingCreate(true);
    }

    const handleReconnectClosed = (didSave:boolean) => {
        if(didSave) {
            setProcessMessage("Proxy associated, reload to view");
            if(props.relinkedCb) props.relinkedCb();
        } else {
            setProcessMessage("");
        }
        setShowingReconnect(false);
        setPotentialProxies(undefined);
    }
    if(loading) {
        return <div className={classes.centered}>
            <CircularProgress/>
        </div>
    }

    return <div className={props.className}>
        {
            showingCreate ? <>
                <EntryThumbnail mimeType={props.mimeType} fileExtension={props.fileExtension} entryId={props.itemId}/>
                <p className={clsx(classes.thumbnote, classes.centered)}>There is no {selectedPreview} proxy available</p>
                <Grid direction="row" container style={{marginTop: "0.4em"}} justify="space-around">
                    <Grid item>
                        <Button variant="outlined"
                                onClick={initiateCreateProxy}>
                            Create
                        </Button>
                    </Grid>
                    <Grid item>
                        {
                            potentialProxies ?  <Button variant="outlined"
                                                    onClick={()=>setShowingReconnect(true)}>
                                Reconnect
                                </Button>:
                                <Button variant="outlined"
                                    onClick={initiateRelinkSearch}>
                                Re-check
                            </Button>
                        }
                    </Grid>
                </Grid>
            </> : <MediaPlayer entryId={props.itemId}
                               onError={props.onError}
                               mimeType={props.mimeType}
                               playableType={selectedPreview}
                               onProxyNotFound={handleProxyNotFound}
                               autoPlay={autoPlay}/>
        }
        <hr className={classes.partialDivider}/>
        <EntryPreviewSwitcher availableTypes={proxyLocations.map(loc=>loc.proxyType).join(",")} typeSelected={newTypeSelected}/>
        { processMessage ? <Typography className={clsx(classes.processingText, classes.centered)}>{processMessage}</Typography> : ""}
        <hr className={classes.partialDivider}/>
        {
            showingReconnect && potentialProxies ? <ReconnectDialog potentialProxies={potentialProxies}
                                                itemName={props.itemName}
                                                itemId={props.itemId}
                                                onDialogClose={handleReconnectClosed}
                                                onError={props.onError}
            /> : undefined
        }
    </div>
}

export default MediaPreview;