import React, {useContext, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import EntryJobs from "./EntryJobs.jsx";
import axios from 'axios';
import {formatError} from "../common/ErrorViewComponent.jsx";
import {Button, createStyles, Grid, IconButton, makeStyles, Typography, withStyles} from "@material-ui/core";
import MetadataTable from "./details/MetadataTable";
import LightboxInsert from "./details/LightboxInsert";
import {baseStyles} from "../BaseStyles";
import MediaPreview from "./MediaPreview";
import {ArchiveEntry} from "../types";
import {UserContext} from "../Context/UserContext";

const useLocalStyles = makeStyles((theme)=>({
    entryDetails: {
        overflow: "auto",
        height: "100%",
    },
    entryDetailsLightboxes: {
        marginLeft: "1.5em"
    },
    partialDivider: {
        width: "70%"
    },
    centeredMedia: {
        marginLeft: "auto",
        marginRight: "auto",
        width: "100%",
        textAlign: "center",
    }
}));

const useBaseStyles = makeStyles(baseStyles);

interface EntryDetailsProps {
    entry: ArchiveEntry;
    autoPlay?: boolean;
    showJobs?: boolean;
    loadJobs?: boolean;
    lightboxedCb?: (entryId:string)=>any;

    preLightboxInsert?: React.ReactElement;
    postLightboxInsert?: React.ReactElement;
    postJobsInsert?: React.ReactElement;
    tableRowsInsert?: React.ReactElement;
    onError?: (errorDesc:string)=>void;
    openClicked?: (entryId:string)=>void;
}

const EntryDetails:React.FC<EntryDetailsProps> = (props) => {
    const [jobsAutorefresh, setJobsAutorefresh] = useState(false);
    const [loading, setLoading] = useState(false);

    const userContext = useContext(UserContext);

    const classes = useLocalStyles();
    const baseClasses = useBaseStyles();

    const proxyGenerationWasTriggered = () => {
        console.log("new proxy generation was started");
        setJobsAutorefresh(true);
    }

    const triggerAnalyse = async () => {
        setLoading(true);
        try {
            await axios.post(`/api/proxy/analyse/${props.entry.id}`);
            console.log("Media analyse started");
            setLoading(false);
            setJobsAutorefresh(true);
        } catch(err) {
            console.error(err);
            if(props.onError) props.onError(formatError(err, true));
            setLoading(false);
        }
    }

    //ensure that autorefresh is switched off if we switch entries
    useEffect(()=>{
        setJobsAutorefresh(false);
    }, [props.entry]);

    const isInLightbox = () => {
        const matchingEntries = props.entry.lightboxEntries.filter(lbEntry=>lbEntry.owner===userContext.profile?.email);
        return matchingEntries.length>0;
    }

    return (
        <div className={classes.entryDetails}>
            <MediaPreview itemId={props.entry.id}
                          itemName={props.entry.path}
                          fileExtension={props.entry.file_extension}
                          mimeType={props.entry.mimeType}
                          relinkedCb={()=>props.lightboxedCb ? props.lightboxedCb(props.entry.id) : undefined}  //use the lightboxCb to indicate that the item needs reloading
                          triggeredProxyGeneration={proxyGenerationWasTriggered}
                          className={classes.centeredMedia}
            />
            <div className="entry-details-insert">{ props.preLightboxInsert ? props.preLightboxInsert : undefined }</div>
            <div className={classes.entryDetailsLightboxes}>
                <LightboxInsert isInLightbox={isInLightbox()}
                                entryId={props.entry.id}
                                onError={props.onError}
                                lightboxedCb={props.lightboxedCb}
                                lightboxEntries={props.entry.lightboxEntries}
                />
            </div>
            <hr className={classes.partialDivider}/>
            <div className="entry-details-insert">{ props.postLightboxInsert ? props.postLightboxInsert : undefined }</div>
            {
                props.showJobs ? <EntryJobs entryId={props.entry.id}
                                            loadImmediate={props.loadJobs}
                                            autoRefresh={jobsAutorefresh}
                                            autoRefreshUpdated={(newValue:boolean)=>setJobsAutorefresh(newValue)}
                /> : undefined
            }
            <div className="entry-details-insert">{ props.postJobsInsert ? props.postJobsInsert : undefined }</div>
            <div className="entry-details-insert">
                <MetadataTable entry={props.entry} openClicked={props.openClicked}/>
            </div>
            <div className={baseClasses.centered} style={{marginTop: "1em"}}>
                <Button variant="outlined" onClick={triggerAnalyse}>Re-analyse metadata</Button>
            </div>
        </div>
    )
}

export default EntryDetails;