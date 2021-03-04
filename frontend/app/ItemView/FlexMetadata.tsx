import React, {useEffect, useState} from "react";
import {ArchiveEntry} from "../types";
import {Grid, makeStyles, Paper} from "@material-ui/core";
import FlexMetadataEntry from "./FlexMetadataEntry";
import PathDisplayComponent from "../browse/PathDisplayComponent";
import {Storage} from "@material-ui/icons";
import {extractFileInfo} from "../common/Fileinfo";
import Chip from '@material-ui/core/Chip';

interface FlexMetadataProps {
    entry: ArchiveEntry
    jumpToPath?: ()=>void;
}

const useStyles = makeStyles((theme)=>({
    metadataEntry: {
        margin: "1em"
    },
    title: {
        marginLeft: "0.4em"
    },
    chip: {
        margin: theme.spacing(0.5)
    },
    chipContainer: {
        margin: 0,
        padding: 0
    }
}));

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
                    <FlexMetadataEntry className={classes.metadataEntry} label="Duration" value={props.entry.mediaMetadata.format.duration.toString()}/>
                    <FlexMetadataEntry className={classes.metadataEntry} label="Bit rate" value={props.entry.mediaMetadata.format.bit_rate.toString()}/>
                    <FlexMetadataEntry className={classes.metadataEntry} label="Format" value={props.entry.mediaMetadata.format.format_long_name}/>
                    <FlexMetadataEntry className={classes.metadataEntry} label="File size" value={props.entry.mediaMetadata.format.size.toString()}/>
                    <FlexMetadataEntry className={classes.metadataEntry} label="Format tags" value={
                        <Grid container className={classes.chipContainer}>
                        {
                            Object.keys(props.entry.mediaMetadata.format.tags)
                                .map(k=><Grid item>
                                    <Chip
                                        label={`${k}: ${props.entry.mediaMetadata?.format.tags[k]}`}
                                        key={k}
                                        className={classes.chip}
                                        />
                                </Grid>)
                        }
                        </Grid>
                    }/>
                </> : undefined
            }

        </Grid>
        </>
}

export default FlexMetadata;