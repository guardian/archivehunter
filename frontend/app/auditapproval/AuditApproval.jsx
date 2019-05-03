import React from 'react';
import axios from 'axios';
import RestoreStatsChart from './RestoreStatsChart.jsx';

class AuditApproval extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            mainGraphType: "Restore",
            mainGraphCat: "user"
        }
    }

    render(){
        return <div>
            <RestoreStatsChart graphValues={this.state.mainGraphType} graphCategory={this.state.mainGraphCat}/>
            <div style={{display:"inline"}}>
                <select value={this.state.mainGraphType} onChange={evt=>this.setState({mainGraphType: evt.target.value})}>
                    <option value="Restore">Show Restores</option>
                    <option value="Download">Show Downloads</option>
                </select>
                <br/>
                <select value={this.state.mainGraphCat} onChange={evt=>this.setState({mainGraphCat: evt.target.value})}>
                    <option value="user">Group by users</option>
                    <option value="region">Group by regions</option>
                    <option value="collection">Group by archive collection</option>
                </select>
            </div>
        </div>
    }
}

export default AuditApproval;