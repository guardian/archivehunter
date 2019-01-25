import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import ErrorViewComponent from "../../common/ErrorViewComponent.jsx";
import LoadingThrobber from "../../common/LoadingThrobber.jsx";
import {Redirect} from "react-router-dom";

class ConfirmationComponent extends React.Component {
    static propTypes = {
        foundBySearch: PropTypes.string.isRequired,
        selectedDeployment: PropTypes.string.isRequired,
        manualInput: PropTypes.object.isRequired
    };

    static stackIdRegex = /^\w+:\w+:cloudformation:([\w\d\-]+):\d+:stack\/([^/]+)\/(.*)$/;

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            completed: false,
            redirecting: false
        };
        
        this.performConnection = this.performConnection.bind(this);
    }

    static breakdownStackId(stackId){
        const results = ConfirmationComponent.stackIdRegex.exec(stackId);
        if(!results){
            console.error("Stack id ", stackId, " is not valid");
            return null;
        } else {
            return {region: results[1], stackName: results[2], uuid: results[3]}
        }
    }

    connectExistingStack(){
        const stackInfo = ConfirmationComponent.breakdownStackId(this.props.selectedDeployment);

        const request = {
            region: stackInfo.region,
            stackName: stackInfo.stackName
        };

        axios.post("/api/proxyFramework/deployments",request).then(response=>{
            this.setState({loading: false, completed: true, redirecting: true});
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err})
        })
    }

    connectManual(){

    }

    performConnection(){
        this.setState({loading: true, lastError: null}, ()=>this.props.foundBySearch ? this.connectExistingStack() : this.connectManual())
    }
    render(){
        if(this.state.redirecting) return <Redirect to="/admin/proxyFramework"/>;
        return <div>
            <h3>Confirmation</h3>
            {
                this.props.foundBySearch ? 
                    <p>The existing Cloudformation stack {this.props.selectedDeployment} will be connected to this Archive Hunter instance.</p> :
                    <p>Archive Hunter will attempt to contact the topics:
                        <ul>
                            <li>{this.props.manualInput ? this.props.manualInput.inputTopic : "(none)"}</li>
                            <li>{this.props.manualInput ? this.props.manualInput.replyTopic : "(none)"}</li>
                            <li>{this.props.manualInput ? this.props.manualInput.managementRole : "(none)"}</li>
                        </ul>
                    </p>
            }
            <ErrorViewComponent error={this.state.lastError}/>
            <LoadingThrobber show={this.state.loading} small={true} caption="Saving..."/>
            <button onClick={this.performConnection} style={{disabled: this.state.loading, float: "right"}}>Confirm</button>
        </div>
    }
}

export default ConfirmationComponent;