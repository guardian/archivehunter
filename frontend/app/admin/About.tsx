import React, {useState, useEffect} from "react";
import axios from "axios";
import {RouteComponentProps} from "react-router";
import AdminContainer from "./AdminContainer"
import ErrorViewComponent from "../common/ErrorViewComponent";
import TimestampFormatter from "../common/TimestampFormatter";
import {Button, Typography} from "@material-ui/core";
import {Helmet} from "react-helmet";

interface VersionInfo {
    buildNumber: number;
    buildBranch: string;
    buildDate: string;
}

const AboutComponent:React.FC<RouteComponentProps> = (props) => {
    const [versionInfo, setVersionInfo] = useState<VersionInfo>({buildNumber: -1, buildBranch: "not loaded", buildDate: ""})
    const [lastError, setLastError] = useState<Error|undefined>(undefined);
    const [didStartDataMigration, setDidStartDataMigration] = useState(false);

    useEffect(()=>{
        axios.get("/api/version").then(response=>{
            setVersionInfo(response.data);
            setLastError(undefined);
        }).catch(err=>{
            setLastError(err)
        })
    }, []);

    const requestDataMigration = async ()=>{
        try {
            await axios.post("/api/rundatamigration");
            setDidStartDataMigration(true);
        } catch(err) {
            setLastError(err);
        }
    }

    return <AdminContainer {...props}>
        <Helmet>
            <title>About - ArchiveHunter</title>
        </Helmet>
        {
            lastError ?
                <ErrorViewComponent error={lastError}/> :
                <div>
                    <Typography variant="h5">You are running build number <b>{versionInfo.buildNumber}</b></Typography>
                    <Typography>This was built from the <b>{versionInfo.buildBranch}</b> branch at <TimestampFormatter relative={false} value={versionInfo.buildDate}/></Typography>
                </div>
        }
        <div>
            <Button variant="outlined" onClick={requestDataMigration} disabled={didStartDataMigration}>Run data migration</Button>
            {
                didStartDataMigration ? <div>
                    <Typography>Data migration operations started, please monitor the server logs</Typography>
                </div> : undefined
            }
        </div>
        </AdminContainer>
}

export default AboutComponent;