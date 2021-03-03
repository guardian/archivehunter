import React, {useEffect, useState} from "react";
import {ArchiveEntry} from "../types";
import {Grid, makeStyles} from "@material-ui/core";
import FlexMetadataEntry from "./FlexMetadataEntry";
import PathDisplayComponent from "../browse/PathDisplayComponent";
import {Storage} from "@material-ui/icons";
import {extractFileInfo} from "../common/Fileinfo";

interface FlexMetadataProps {
    entry: ArchiveEntry
    jumpToPath?: ()=>void;
}

const useStyles = makeStyles({
    metadataEntry: {
        margin: "1em"
    },
    title: {
        marginLeft: "0.4em"
    }
})
const FlexMetadata:React.FC<FlexMetadataProps> = (props) => {
    const classes = useStyles();

    const fileInfo = extractFileInfo(props.entry.path);

    return <>
        <h1 className={classes.title}>{fileInfo.filename}</h1>
        <Grid container>
            {
                //FIXME: should put functionality on browse to open a specific set of paths
            }

            <FlexMetadataEntry className={classes.metadataEntry}
                               label="Path"
                               value={<PathDisplayComponent path={fileInfo.filepath}/>}
                               callout={props.jumpToPath}
            />
            <FlexMetadataEntry className={classes.metadataEntry} label="Collection" value={props.entry.bucket} icon={<Storage/>}/>
            <FlexMetadataEntry className={classes.metadataEntry} label="Storage class" value={props.entry.storageClass}/>
            <FlexMetadataEntry className={classes.metadataEntry} label="Region" value={props.entry.region ?? "(not set)"}/>
        </Grid>
        </>
}

export default FlexMetadata;