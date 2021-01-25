import React, {useState, useEffect} from "react";
import axios from "axios";
import {RouteComponentProps} from "react-router";
import AdminContainer from "./AdminContainer"
import ErrorViewComponent from "../common/ErrorViewComponent";
import TimestampFormatter from "../common/TimestampFormatter";
import {Typography} from "@material-ui/core";

interface VersionInfo {
    buildNumber: number;
    buildBranch: string;
    buildDate: string;
}

const AboutComponent:React.FC<RouteComponentProps> = (props) => {
    const [versionInfo, setVersionInfo] = useState<VersionInfo>({buildNumber: -1, buildBranch: "not loaded", buildDate: ""})
    const [lastError, setLastError] = useState<Error|undefined>(undefined);

    useEffect(()=>{
        axios.get("/api/version").then(response=>{
            setVersionInfo(response.data);
            setLastError(undefined);
        }).catch(err=>{
            setLastError(err)
        })
    }, []);

    return <div>
        <AdminContainer {...props}>
        {
            lastError ?
                <ErrorViewComponent error={lastError}/> :
                <div>
                    <Typography variant="h5">You are running build number <b>{versionInfo.buildNumber}</b></Typography>
                    <Typography>This was built from the <b>{versionInfo.buildBranch}</b> branch at <TimestampFormatter relative={false} value={versionInfo.buildDate}/></Typography>
                </div>
        }
        </AdminContainer>
    </div>
}

export default AboutComponent;