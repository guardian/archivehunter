import React, {useEffect, useState} from "react";
import {RouteComponentProps} from "react-router";
import AdminContainer from "./AdminContainer";
import {Helmet} from "react-helmet";
import {Button, FormControlLabel, Input, Paper, Snackbar, TextField, Typography} from "@material-ui/core";
import MuiAlert from "@material-ui/lab/Alert";
import {formatError} from "../common/ErrorViewComponent";
import axios from "axios";

interface ManualImporterProps {

}

const ManualImporter:React.FC<RouteComponentProps<ManualImporterProps>> = (props) => {
    const [bucketName, setBucketName] = useState("");
    const [pathList, setPathList] = useState("");
    const [pathsCount, setPathsCount] = useState(0);
    const [controlsEnabled, setControlsEnabled] = useState(true);
    const [progressText, setProgressText] = useState("");

    useEffect(()=>{
        const newPathCount = pathList.split("\n").length;
        if(newPathCount!=pathsCount) setPathsCount(newPathCount);
    }, [pathList]);

    const singleImportRequest = async (filePath:string) => {
        const req = {
            collectionName: bucketName,
            itemPath: filePath
        };

        const response = await axios.post(`/api/import`,req,{validateStatus: ()=>true});
        switch(response.status) {
            case 200:
                setProgressText(`Import of ${filePath} successful`);
                break;
            case 409:
                setProgressText(`Item ${filePath} was already imported`);
                break;
            case 419:
                throw "Session expired";
            default:
                console.error("Import gave server error ", response.status, response.statusText, response.data);
                throw "Server error";
        }
    }

    const doImport = async ()=>{
        if(pathsCount==0) {
            alert("Nothing to import! Try pasting some paths in first")
        }

        setControlsEnabled(false);
        const toImport = pathList.split("\n");

        let i=0;
        for(let toImportCount=toImport.length;toImportCount>0;toImportCount--){
            setProgressText(`${toImportCount} items to import`);
            try {
                await singleImportRequest(toImport[i]);
            } catch(err) {
                setProgressText(`Import failed: ${err}`);
                setControlsEnabled(true);
                return
            }
            i++
        }
        setControlsEnabled(true);
        setProgressText("Import completed")
    }

    return (
        <AdminContainer {...props}>
            <Helmet>
                <title>Manual import - ArchiveHunter</title>
            </Helmet>

            <Paper elevation={3}>
                <Typography variant="h1">Manual (quick) import</Typography>
                <Typography>
                    You can tell ArchiveHunter to import specific files that already exist in a known bucket ("collection")
                    but that have not got into the index for some reason.
                </Typography>
                <Typography>
                    Simply paste the full paths into the text box below, and fill in the name of the bucket that they
                    belong to.  When you hit the "import" button, the system will try to import them one at a time
                    and will update the status here.
                </Typography>

                <Typography>{progressText}</Typography>
                <Button variant="contained" onClick={doImport} disabled={!controlsEnabled && pathsCount>0}>Import {pathsCount} items</Button>

                <TextField  onChange={(evt)=>setBucketName(evt.target.value)}
                            value={bucketName}
                            label="Bucket name"
                            disabled={!controlsEnabled}
                            />
                <TextField multiline
                           onChange={(evt)=>setPathList(evt.target.value)}
                           value={pathList}
                           label="Paths"
                           disabled={!controlsEnabled}
                           rows={30}
                />
            </Paper>
        </AdminContainer>
    )
}

export default ManualImporter;