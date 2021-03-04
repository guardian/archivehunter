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
});

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
            {
                props.entry.mediaMetadata ? <>
                    <FlexMetadataEntry label="Duration" value={props.entry.mediaMetadata.format.duration.toString()}/>
                    <FlexMetadataEntry label="Bit rate" value={props.entry.mediaMetadata.format.bit_rate.toString()}/>
                    <FlexMetadataEntry label="Format" value={props.entry.mediaMetadata.format.format_long_name}/>
                    <FlexMetadataEntry label="File size" value={props.entry.mediaMetadata.format.size.toString()}/>
                    <FlexMetadataEntry label="Format tags" value={
                        Object.keys(props.entry.mediaMetadata.format.tags)
                            .map((k,idx)=>`${k}: ${props.entry.mediaMetadata?.format.tags[k]}`)
                            .join(",")}
                    />
                </> : undefined
            }

        </Grid>
        </>
}

export default FlexMetadata;