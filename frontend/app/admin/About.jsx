import React from 'react';
import axios from 'axios';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import TimestampFormatter from "../common/TimestampFormatter.jsx";

class AboutComponent extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            versionInfo: {
                buildNumber: null,
                buildBranch: null,
                buildDate: null
            }
        }
    }

    componentWillMount(){
        this.setState({loading: true}, ()=>axios.get("/api/version").then(response=>{
            this.setState({loading: false, lastError: false, versionInfo: response.data})
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err})
        }));
    }

    render(){
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            {
                this.state.lastError ?
                    <ErrorViewComponent error={this.state.lastError}/> :
                    <div>
                        <p className="centered large">You are running build number <b>{this.state.versionInfo.buildNumber}</b></p>
                        <p className="centered">This was built from the <b>{this.state.versionInfo.buildBranch}</b> branch at <TimestampFormatter relative={false} value={this.state.versionInfo.buildDate}/></p>
                    </div>
            }
        </div>
    }
}

export default AboutComponent;