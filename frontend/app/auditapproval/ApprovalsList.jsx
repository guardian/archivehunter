import React from 'react';
import axios from 'axios';
import PropTypes from 'prop-types';
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";

class ApprovalsList extends React.Component {
    constructor(props){
        super(props);
        this.state = {
            loading: false,
            lastError: null,
            selectedStatus: "Pending",
            approvalsList: []
        }
    }

    componentWillMount() {
        this.refreshData();
    }

    refreshData(){
        this.setState({loading: true, lastError: null}, ()=>axios.get("/api/audit/approvals/byStatus?statusString=" + this.state.selectedStatus).then(response=>{
            this.setState({loading: false, approvalsList: response.data.entries});
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }))
    }

    render(){
        if(this.state.loading) return <LoadingThrobber show={true} caption="loading"/>;
        if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;
        return <div>
            <ul className="approvals-list">
                {this.state.approvalsList.map(listEntry=><ApprovalsListEntry data={listEntry}/>)}
            </ul>
        </div>
    }
}

export default ApprovalsList;
